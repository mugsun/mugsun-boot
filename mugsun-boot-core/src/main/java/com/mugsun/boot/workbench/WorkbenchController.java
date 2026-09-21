package com.mugsun.boot.workbench;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.gen.DbDialects;
import com.mugsun.boot.gen.RuntimeSql;
import com.mugsun.boot.workbench.entity.SysWorkbenchShortcut;
import com.mugsun.boot.workbench.mapper.SysWorkbenchShortcutMapper;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 首页工作台：概览统计 + 图表数据聚合（内置白名单 SQL）+ 每用户快捷入口持久化。
 * 待办/未读通知与 FlowController、SysNoticeController 同口径，杜绝穿透。
 */
@RestController
@RequestMapping("/system/workbench")
@SaCheckLogin
public class WorkbenchController {

	/** 通知管理权限码：持有者（超管通配）不受可见范围限制，与 SysNoticeController 同口径 */
	private static final String NOTICE_MANAGE = "sys:notice:manage";

	private final SysWorkbenchShortcutMapper shortcutMapper;

	/** 金仓：裸 sys_* 易进 SYS_CATALOG；配置后限定业务 schema */
	@Value("${mugsun.db.default-schema:}")
	private String defaultDbSchema;

	public WorkbenchController(SysWorkbenchShortcutMapper shortcutMapper) {
		this.shortcutMapper = shortcutMapper;
	}

	private String bizTable(String table) {
		return StringUtils.hasText(defaultDbSchema) ? defaultDbSchema + "." + table : table;
	}

	/** 概览：统计卡计数 + 图表聚合数据（按当前租户隔离；超管「查看全部」视图才给全平台口径） */
	@GetMapping("/overview")
	public R<Map<String, Object>> overview() {
		String tenant = com.mugsun.boot.tenant.TenantContext.current();
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("userCount", count(bizTable("sys_user"), tenant));
		data.put("deptCount", count(bizTable("sys_dept"), tenant));
		data.put("roleCount", count(bizTable("sys_role"), tenant));
		data.put("todoCount", todoCount());
		data.put("noticeUnread", noticeUnread());

		Map<String, Object> charts = new LinkedHashMap<>();
		// 白名单聚合 SQL（预置常量，杜绝注入），返回 [{name,value}]
		String userTbl = bizTable("sys_user");
		if (tenant == null) {
			charts.put("userStatus", Db.selectListBySql(
				"SELECT CASE status WHEN 1 THEN '启用' ELSE '停用' END AS \"name\", count(*) AS \"value\" "
					+ "FROM " + userTbl + " WHERE is_deleted = 0 GROUP BY status ORDER BY status"));
		} else {
			charts.put("userStatus", Db.selectListBySql(
				"SELECT CASE status WHEN 1 THEN '启用' ELSE '停用' END AS \"name\", count(*) AS \"value\" "
					+ "FROM " + userTbl + " WHERE is_deleted = 0 AND tenant_id = ? GROUP BY status ORDER BY status", tenant));
		}
		// 逐租户分布属平台级敏感运营数据：仅「查看全部」或平台超管可见；普通租户管理员不下发该键，前端隐藏卡片（避免空态误导）
		if (tenant == null || com.mugsun.boot.tenant.TenantContext.isPlatformSuperAdmin()) {
			charts.put("tenantUser", Db.selectListBySql(
				"SELECT COALESCE(tenant_id, '未分配') AS \"name\", count(*) AS \"value\" "
					+ "FROM " + userTbl + " WHERE is_deleted = 0 GROUP BY tenant_id ORDER BY tenant_id"));
		}
		data.put("charts", charts);
		return R.data(data);
	}

	/** 当前用户快捷入口 JSON（无则 null，前端用内置默认） */
	@GetMapping("/shortcuts")
	public R<String> shortcuts() {
		SysWorkbenchShortcut cfg = shortcutMapper.selectOneByQuery(
			QueryWrapper.create().eq("user_id", StpUtil.getLoginIdAsLong()));
		return R.data(cfg == null ? null : cfg.getConfigJson());
	}

	/** 保存快捷入口（原子 upsert，按 user_id 归并，对齐 G43/G44） */
	@PostMapping("/shortcuts")
	public R<Void> saveShortcuts(@RequestBody Map<String, String> body) {
		Db.updateBySql(RuntimeSql.upsertWorkbenchShortcut(DbDialects.current()),
			IdUtil.getSnowflakeNextId(), StpUtil.getLoginIdAsLong(), body.get("configJson"));
		return R.success("保存成功");
	}

	/** 逻辑未删计数（表名为常量字面量，非用户输入）；tenant 非空时按租户过滤 */
	private long count(String table, String tenant) {
		Row row = tenant == null
			? Db.selectOneBySql("select count(*) as \"c\" from " + table + " where is_deleted = 0")
			: Db.selectOneBySql("select count(*) as \"c\" from " + table + " where is_deleted = 0 and tenant_id = ?", tenant);
		return longVal(row, "c");
	}

	/** 达梦 JDBC 别名常折成大写，取值时大小写兜底避免 NPE */
	private static long longVal(Row row, String col) {
		if (row == null) {
			return 0;
		}
		Long v = row.getLong(col);
		if (v == null) {
			v = row.getLong(col.toUpperCase());
		}
		if (v == null) {
			v = row.getLong(col.toLowerCase());
		}
		return v == null ? 0 : v;
	}

	/** 我的待办数（与 FlowController.myTodo 同口径：按角色码/用户标识匹配办理人 + 实例按发起人租户隔离） */
	private long todoCount() {
		List<String> flags = userFlags();
		String in = flags.stream().map(f -> "?").collect(Collectors.joining(","));
		String tenant = com.mugsun.boot.tenant.TenantContext.current();
		List<Object> args = new ArrayList<>(flags);
		String tenantSql = "";
		if (tenant != null) {
			tenantSql = " and i.create_by in (select " + DbDialects.current().castVarchar("id") + " from " + bizTable("sys_user")
				+ " where tenant_id = ? and is_deleted = 0)";
			args.add(tenant);
		}
		Row row = Db.selectOneBySql(
			"select count(*) as \"c\" from flow_task t "
				+ "join flow_user u on u.associated = t.id and u.type = '1' and coalesce(u.del_flag, '0') <> '1' "
				+ "join flow_instance i on i.id = t.instance_id "
				+ "where coalesce(t.del_flag, '0') <> '1' and u.processed_by in (" + in + ")" + tenantSql,
			args.toArray());
		return longVal(row, "c");
	}

	/** 我的未读通知数（与 SysNoticeController.myUnreadCount 同口径：管理员见全部，普通用户按可见范围） */
	private long noticeUnread() {
		Long userId = StpUtil.getLoginIdAsLong();
		StringBuilder sql = new StringBuilder("select count(*) as \"c\" from " + bizTable("sys_notice")
			+ " n where n.is_deleted = 0 ");
		List<Object> args = new ArrayList<>();
		if (!StpUtil.hasPermission(NOTICE_MANAGE)) {
			Row u = Db.selectOneBySql("select dept_id as \"deptId\" from " + bizTable("sys_user") + " where id = ?", userId);
			Long deptId = u == null ? null : u.getLong("deptId");
			long dept = deptId == null ? -1L : deptId;
			sql.append("and (n.all_visible = 1 or n.id in (select notice_id from " + bizTable("sys_notice_scope")
				+ " where is_deleted = 0 and ((scope_type = 1 and scope_id = ?) or (scope_type = 2 and scope_id = ?)))) ");
			args.add(dept);
			args.add(dept);
		}
		sql.append("and n.id not in (select notice_id from " + bizTable("sys_notice_read")
			+ " where is_deleted = 0 and user_id = ?)");
		args.add(userId);
		Row row = Db.selectOneBySql(sql.toString(), args.toArray());
		return longVal(row, "c");
	}

	/** 当前用户身份标识集合：角色码 + 用户 id 字符串 */
	private List<String> userFlags() {
		List<String> flags = new ArrayList<>();
		Db.selectListBySql(
			"select r.role_code as \"roleCode\" from " + bizTable("sys_user_role") + " ur "
				+ "join " + bizTable("sys_role") + " r on r.id = ur.role_id where ur.user_id = ? and ur.is_deleted = 0",
			StpUtil.getLoginIdAsLong())
			.forEach(row -> flags.add(String.valueOf(row.getString("roleCode"))));
		flags.add(StpUtil.getLoginIdAsString());
		return flags;
	}
}
