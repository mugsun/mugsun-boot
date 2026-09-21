package com.mugsun.boot.system.service;

import com.mugsun.boot.common.constant.FlowConstants;
import com.mugsun.boot.common.constant.RoleConstants;
import com.mugsun.boot.config.BizTables;
import com.mybatisflex.core.row.Db;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 审批候选人解析引擎：把节点 permissionFlag 里的 storageId（role:/dept:/post:/user: 前缀或裸值）
 * 解析、合并成实际用户 id 集；并提供发起人部门负责人、兜底超管等派生查询。
 * <p>上下文无关的前缀在 PermissionHandler.convertPermissions 落库前解析；需实例上下文的占位
 * （发起人/部门负责人）在 assignment 监听器解析。
 */
@Service
public class HandlerSelectService {

	/** 静态前缀解析：role:/dept:/post:/user: → 用户 id；占位符与裸值原样透传（交监听器/引擎） */
	public List<String> resolveStatic(List<String> flags) {
		Set<String> ids = new LinkedHashSet<>();
		if (flags == null) {
			return new ArrayList<>();
		}
		for (String f : flags) {
			if (f == null || f.isBlank()) {
				continue;
			}
			if (f.startsWith(FlowConstants.CANDIDATE_ROLE)) {
				ids.addAll(byRole(f.substring(FlowConstants.CANDIDATE_ROLE.length())));
			} else if (f.startsWith(FlowConstants.CANDIDATE_DEPT)) {
				ids.addAll(byDept(f.substring(FlowConstants.CANDIDATE_DEPT.length())));
			} else if (f.startsWith(FlowConstants.CANDIDATE_POST)) {
				ids.addAll(byPost(f.substring(FlowConstants.CANDIDATE_POST.length())));
			} else if (f.startsWith(FlowConstants.CANDIDATE_USER)) {
				ids.add(f.substring(FlowConstants.CANDIDATE_USER.length()));
			} else {
				// 裸值（userId / 旧流程角色码 / 占位符）透传
				ids.add(f);
			}
		}
		return new ArrayList<>(ids);
	}

	/** 某角色码下全部用户 id（按当前租户收口：角色码跨租户同名，候选人解析不得跨租户派单） */
	public List<String> byRole(String roleCode) {
		String tenant = com.mugsun.boot.tenant.TenantContext.current();
		if (tenant == null) {
			return column("select u.id as \"v\" from " + BizTables.of("sys_user") + " u "
				+ "join " + BizTables.of("sys_user_role") + " ur on ur.user_id = u.id and ur.is_deleted = 0 "
				+ "join " + BizTables.of("sys_role") + " r on r.id = ur.role_id and r.is_deleted = 0 "
				+ "where r.role_code = ? and u.is_deleted = 0", roleCode);
		}
		return column("select u.id as \"v\" from " + BizTables.of("sys_user") + " u "
			+ "join " + BizTables.of("sys_user_role") + " ur on ur.user_id = u.id and ur.is_deleted = 0 "
			+ "join " + BizTables.of("sys_role") + " r on r.id = ur.role_id and r.is_deleted = 0 "
			+ "where r.role_code = ? and u.is_deleted = 0 and u.tenant_id = '" + tenantSql(tenant) + "'", roleCode);
	}

	/** 某部门下全部用户 id（按当前租户收口） */
	public List<String> byDept(String deptId) {
		String tenant = com.mugsun.boot.tenant.TenantContext.current();
		if (tenant == null) {
			return column("select id as \"v\" from " + BizTables.of("sys_user") + " where dept_id = ? and is_deleted = 0", asLong(deptId));
		}
		return column("select id as \"v\" from " + BizTables.of("sys_user") + " where dept_id = ? and is_deleted = 0 and tenant_id = '"
			+ tenantSql(tenant) + "'", asLong(deptId));
	}

	/** 某岗位下全部用户 id（按当前租户收口） */
	public List<String> byPost(String postId) {
		String tenant = com.mugsun.boot.tenant.TenantContext.current();
		if (tenant == null) {
			return column("select id as \"v\" from " + BizTables.of("sys_user") + " where post_id = ? and is_deleted = 0", asLong(postId));
		}
		return column("select id as \"v\" from " + BizTables.of("sys_user") + " where post_id = ? and is_deleted = 0 and tenant_id = '"
			+ tenantSql(tenant) + "'", asLong(postId));
	}

	/** 租户号转义（仅容许字母数字，防注入；租户号由服务端会话解析非用户输入） */
	private String tenantSql(String tenant) {
		if (!tenant.matches("^[A-Za-z0-9]+$")) {
			throw new IllegalStateException("非法租户号");
		}
		return tenant;
	}

	/** 某用户所在部门的负责人 id（sys_dept.leader_id） */
	public List<String> deptLeaderByUser(String userId) {
		return column("select d.leader_id as \"v\" from " + BizTables.of("sys_user") + " u "
			+ "join " + BizTables.of("sys_dept") + " d on d.id = u.dept_id and d.is_deleted = 0 "
			+ "where u.id = ? and u.is_deleted = 0 and d.leader_id is not null", asLong(userId));
	}

	/** 兜底办理人：超管用户 id 集（空候选/审批人==发起人时用，fail-closed） */
	public List<String> fallbackHandlers() {
		return byRole(RoleConstants.ADMIN);
	}

	/**
	 * 完整解析办理人（静态前缀 + 发起人/部门负责人占位 + 兜底），供 assignment 监听器与下一审批人预测复用。
	 * 输入可为原始 permissionFlag 拆分串（role:/dept:/...）或已过 convertPermissions 的列表（userId+占位）。
	 */
	public List<String> resolveHandlers(List<String> flags, String initiator) {
		Set<String> resolved = new LinkedHashSet<>();
		for (String p : resolveStatic(flags)) {
			if (FlowConstants.PLACEHOLDER_INITIATOR.equals(p)) {
				if (initiator != null) {
					resolved.add(initiator);
				}
			} else if (FlowConstants.PLACEHOLDER_DEPT_LEADER.equals(p)) {
				if (initiator != null) {
					resolved.addAll(deptLeaderByUser(initiator));
				}
			} else {
				resolved.add(p);
			}
		}
		// 兜底①：空候选 → 超管；连超管都无 → 门控哨兵（fail-closed）
		if (resolved.isEmpty()) {
			resolved.addAll(fallbackHandlers());
			if (resolved.isEmpty()) {
				resolved.add(FlowConstants.GATE_SENTINEL);
			}
		}
		// 兜底②：办理人仅发起人本人 → 追加其他超管，防自审自批卡死
		if (initiator != null && resolved.size() == 1 && resolved.contains(initiator)) {
			fallbackHandlers().stream().filter(id -> !id.equals(initiator)).forEach(resolved::add);
		}
		return new ArrayList<>(resolved);
	}

	/** 用户 id 集 → 显示用 [{id, name}]；非用户标识（哨兵/旧角色码）name 回退为标识本身 */
	public List<java.util.Map<String, Object>> usernames(List<String> userIds) {
		List<java.util.Map<String, Object>> result = new ArrayList<>();
		for (String id : userIds) {
			String name = id;
			if (id != null && id.chars().allMatch(Character::isDigit)) {
				List<String> n = column("select username as \"v\" from " + BizTables.of("sys_user")
					+ " where id = ? and is_deleted = 0", asLong(id));
				if (!n.isEmpty()) {
					name = n.get(0);
				}
			}
			result.add(java.util.Map.of("id", id, "name", name));
		}
		return result;
	}

	private List<String> column(String sql, Object arg) {
		List<String> list = new ArrayList<>();
		Db.selectListBySql(sql, arg).forEach(row -> {
			String v = row.getString("v");
			if (v != null && !v.isBlank()) {
				list.add(v);
			}
		});
		return list;
	}

	private Long asLong(String s) {
		try {
			return Long.valueOf(s.trim());
		} catch (NumberFormatException e) {
			return -1L;
		}
	}
}
