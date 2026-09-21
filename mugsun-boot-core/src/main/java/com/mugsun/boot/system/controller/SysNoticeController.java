package com.mugsun.boot.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.common.constant.WsConstants;
import com.mugsun.boot.common.tx.AfterCommit;
import com.mugsun.boot.datascope.DataScopeContext;
import com.mugsun.boot.gen.DbDialects;
import com.mugsun.boot.gen.RuntimeSql;
import com.mugsun.boot.gen.SqlDialect;
import com.mugsun.boot.system.entity.SysDept;
import com.mugsun.boot.system.entity.SysNotice;
import com.mugsun.boot.system.entity.SysNoticeRead;
import com.mugsun.boot.system.entity.SysNoticeScope;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.mapper.SysDeptMapper;
import com.mugsun.boot.system.mapper.SysNoticeMapper;
import com.mugsun.boot.system.mapper.SysNoticeReadMapper;
import com.mugsun.boot.system.mapper.SysNoticeScopeMapper;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.websocket.WsFrame;
import com.mugsun.boot.websocket.WsMessageSender;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 通知公告管理：管理端 CRUD + 可见范围/阅读记录；用户端「我的通知」按可见范围过滤 + 已读留痕。
 */
@RestController
@RequestMapping("/system/notice")
@SaCheckLogin
public class SysNoticeController {

	private static final String PERM_MANAGE = "sys:notice:manage";

	private static final Logger log = LoggerFactory.getLogger(SysNoticeController.class);

	private final SysNoticeMapper noticeMapper;
	private final SysNoticeScopeMapper scopeMapper;
	private final SysNoticeReadMapper readMapper;
	private final SysUserMapper userMapper;
	private final SysDeptMapper deptMapper;
	private final WsMessageSender wsMessageSender;

	public SysNoticeController(SysNoticeMapper noticeMapper, SysNoticeScopeMapper scopeMapper,
							   SysNoticeReadMapper readMapper, SysUserMapper userMapper, SysDeptMapper deptMapper,
							   WsMessageSender wsMessageSender) {
		this.noticeMapper = noticeMapper;
		this.scopeMapper = scopeMapper;
		this.readMapper = readMapper;
		this.userMapper = userMapper;
		this.deptMapper = deptMapper;
		this.wsMessageSender = wsMessageSender;
	}

	// ============ 管理端 ============

	/** 分页：置顶优先，再按时间倒序；支持按分类过滤 */
	@GetMapping("/page")
	@SaCheckPermission(PERM_MANAGE)
	public R<Page<SysNotice>> page(@RequestParam(defaultValue = "1") long pageNum,
								   @RequestParam(defaultValue = "10") long pageSize,
								   @RequestParam(required = false) String category) {
		QueryWrapper query = QueryWrapper.create().orderBy("is_top", false).orderBy("id", false);
		if (category != null && !category.isBlank()) {
			query.eq("category", category);
		}
		return R.data(noticeMapper.paginate(pageNum, pageSize, query));
	}

	/** 置顶公告列表（非管理端用户同样按可见范围过滤，防定向公告标题经 top 旁路泄露） */
	@GetMapping("/top")
	public R<List<SysNotice>> top() {
		QueryWrapper query = QueryWrapper.create().eq("is_top", 1).orderBy("id", false);
		applyVisibleScope(query);
		return R.data(noticeMapper.selectListByQuery(query));
	}

	/** 详情：附可见范围明细（回显穿梭框）；非管理端越范围访问按不存在处理（防遍历 id 读定向公告） */
	@GetMapping("/detail")
	public R<SysNotice> detail(@RequestParam Long id) {
		SysNotice notice = noticeMapper.selectOneById(id);
		if (notice == null || !visibleToMe(id)) {
			return R.data(null);
		}
		notice.setScopeList(scopeMapper
			.selectListByQuery(QueryWrapper.create().eq("notice_id", id))
			.stream()
			.map(s -> new SysNotice.NoticeScopeItem(s.getScopeType(), s.getScopeId()))
			.toList());
		return R.data(notice);
	}

	/** 新增/更新：主体 + 可见范围（先删旧再插新）；全员可见公告提交后实时推送在线用户 */
	@PostMapping("/submit")
	@SaCheckPermission(PERM_MANAGE)
	@Transactional(rollbackFor = Exception.class)
	public R<Void> submit(@RequestBody SysNotice notice) {
		if (notice.getAllVisible() == null) {
			notice.setAllVisible(1);
		}
		// 租户归属服务端裁定：新增置 null 交 Flex 填当前租户；更新以库内存量行为准（防请求体伪造 tenantId 跨租户注入公告/广播）
		String authoritativeTenant;
		if (notice.getId() == null) {
			notice.sanitizeForInsert();
			notice.setTenantId(null);
			if (notice.getReleaseTime() == null) {
				notice.setReleaseTime(LocalDateTime.now());
			}
			noticeMapper.insertSelective(notice);
			authoritativeTenant = notice.getTenantId();
		} else {
			SysNotice exist = noticeMapper.selectOneById(notice.getId());
			if (exist == null) {
				throw new ServiceException("公告不存在");
			}
			authoritativeTenant = exist.getTenantId();
			notice.sanitizeForUpdate();
			notice.setTenantId(null);
			noticeMapper.update(notice);
		}
		Long noticeId = notice.getId();
		scopeMapper.deleteByQuery(QueryWrapper.create().eq("notice_id", noticeId));
		if (notice.getAllVisible() == 0 && notice.getScopeList() != null) {
			for (SysNotice.NoticeScopeItem item : notice.getScopeList()) {
				SysNoticeScope scope = new SysNoticeScope();
				scope.setNoticeId(noticeId);
				scope.setScopeType(item.scopeType());
				scope.setScopeId(item.scopeId());
				scopeMapper.insertSelective(scope);
			}
		}
		// 实时推送仅覆盖全员可见公告：指定范围公告不推（避免标题泄露给范围外用户），由前端轮询未读数兜底
		if (Integer.valueOf(1).equals(notice.getAllVisible())) {
			pushNoticeNew(notice, authoritativeTenant);
		}
		return R.success("操作成功");
	}

	@PostMapping("/remove")
	@SaCheckPermission(PERM_MANAGE)
	@Transactional(rollbackFor = Exception.class)
	public R<Void> remove(@RequestBody List<Long> ids) {
		noticeMapper.deleteBatchByIds(ids);
		for (Long id : ids) {
			scopeMapper.deleteByQuery(QueryWrapper.create().eq("notice_id", id));
		}
		return R.success("删除成功");
	}

	/** 阅读记录分页（谁读了 + 次数 + 首末时间），装配昵称/部门 */
	@GetMapping("/read/page")
	@SaCheckPermission(PERM_MANAGE)
	public R<Page<SysNoticeRead>> readPage(@RequestParam Long noticeId,
										   @RequestParam(defaultValue = "1") long pageNum,
										   @RequestParam(defaultValue = "10") long pageSize) {
		Page<SysNoticeRead> page = readMapper.paginate(pageNum, pageSize,
			QueryWrapper.create().eq("notice_id", noticeId).orderBy("last_time", false));
		fillReaders(page.getRecords());
		return R.data(page);
	}

	// ============ 用户端「我的通知」 ============

	/** 我可见的通知分页：all_visible=1 或命中可见范围；管理员见全部；置顶优先 */
	@GetMapping("/my/page")
	public R<Page<SysNotice>> myPage(@RequestParam(defaultValue = "1") long pageNum,
									 @RequestParam(defaultValue = "10") long pageSize,
									 @RequestParam(required = false) String category) {
		QueryWrapper query = QueryWrapper.create();
		if (category != null && !category.isBlank()) {
			query.eq("category", category);
		}
		applyVisibleScope(query);
		query.orderBy("is_top", false).orderBy("id", false);
		Page<SysNotice> page = noticeMapper.paginate(pageNum, Math.min(pageSize, 500), query);
		fillReadFlag(page.getRecords());
		return R.data(page);
	}

	/** 标记已读（幂等 upsert）：首读 view_uv+1，每读 view_pv+1、read_count+1；通知须存在且当前用户可见 */
	@PostMapping("/read/{noticeId}")
	@Transactional(rollbackFor = Exception.class)
	public R<Void> read(@PathVariable Long noticeId) {
		Long userId = StpUtil.getLoginIdAsLong();
		if (!visibleToMe(noticeId)) {
			throw new ServiceException("通知不存在");
		}
		// PG：原子 upsert + xmax 判首读；Oracle/达梦无 xmax，应用层先查后写
		boolean firstRead;
		SqlDialect d = DbDialects.current();
		if (d.oracleFamily()) {
			Row existed = Db.selectOneBySql(
				"select id from " + com.mugsun.boot.config.BizTables.of("sys_notice_read")
					+ " where notice_id = ? and user_id = ? and is_deleted = 0"
					+ d.limitOne(),
				noticeId, userId);
			if (existed == null) {
				Db.updateBySql(RuntimeSql.insertNoticeRead(d),
					IdUtil.getSnowflakeNextId(), noticeId, userId);
				firstRead = true;
			} else {
				Db.updateBySql(RuntimeSql.bumpNoticeRead(d), noticeId, userId);
				firstRead = false;
			}
		} else {
			Row row = Db.selectOneBySql(RuntimeSql.upsertNoticeReadPg(),
				IdUtil.getSnowflakeNextId(), noticeId, userId);
			firstRead = row != null && Boolean.TRUE.equals(row.getBoolean("first_read"));
		}
		Db.updateBySql("update " + com.mugsun.boot.config.BizTables.of("sys_notice")
			+ " set view_pv = view_pv + 1" + (firstRead ? ", view_uv = view_uv + 1" : "")
			+ " where id = ?", noticeId);
		return R.success("已读");
	}

	/** 我的未读通知数（可见范围内、未读过的） */
	@GetMapping("/my/unread-count")
	public R<Long> myUnreadCount() {
		Long userId = StpUtil.getLoginIdAsLong();
		QueryWrapper query = QueryWrapper.create();
		applyVisibleScope(query);
		query.and("id not in (select notice_id from " + com.mugsun.boot.config.BizTables.of("sys_notice_read")
			+ " where is_deleted = 0 and user_id = ?)", userId);
		return R.data(noticeMapper.selectCountByQuery(query));
	}

	// ============ 内部工具 ============

	/** 可见范围条件（非管理端）：all_visible=1 或命中本人/本部门范围；管理端不附加 */
	private void applyVisibleScope(QueryWrapper query) {
		if (StpUtil.hasPermission(PERM_MANAGE)) {
			return;
		}
		Long userId = StpUtil.getLoginIdAsLong();
		Long deptId = deptIdOf(userId);
		query.and("(all_visible = 1 or id in (select notice_id from "
			+ com.mugsun.boot.config.BizTables.of("sys_notice_scope")
			+ " where is_deleted = 0 and ((scope_type = 1 and scope_id = ?) or (scope_type = 2 and scope_id = ?))))",
			userId, deptId == null ? -1L : deptId);
	}

	/** 当前用户是否可见该通知（存在 + 租户内 + 可见范围）；管理端恒可见 */
	private boolean visibleToMe(Long noticeId) {
		if (noticeId == null) {
			return false;
		}
		SysNotice notice = noticeMapper.selectOneById(noticeId);
		if (notice == null) {
			return false;
		}
		if (StpUtil.hasPermission(PERM_MANAGE)) {
			return true;
		}
		if (Integer.valueOf(1).equals(notice.getAllVisible())) {
			return true;
		}
		Long userId = StpUtil.getLoginIdAsLong();
		Long deptId = deptIdOf(userId);
		return scopeMapper.selectCountByQuery(QueryWrapper.create().eq("notice_id", noticeId)
			.and("((scope_type = 1 and scope_id = ?) or (scope_type = 2 and scope_id = ?))",
				userId, deptId == null ? -1L : deptId)) > 0;
	}

	/** 公告实时推送：本方法在事务内被调用，注册提交后推送；推送失败不影响主流程。
	 *  租户以落库权威值为准（不信任请求体），防跨租户广播注入。 */
	private void pushNoticeNew(SysNotice notice, String authoritativeTenant) {
		// 无权威租户（超管查看全部租户的边界场景）则跳过
		String tenantId = authoritativeTenant != null ? authoritativeTenant : TenantContext.current();
		if (tenantId == null) {
			return;
		}
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("noticeId", notice.getId());
		content.put("title", notice.getTitle());
		WsFrame frame = WsFrame.of(WsConstants.NOTICE_NEW, content);
		Runnable push = () -> {
			try {
				wsMessageSender.sendToTenant(tenantId, frame);
			} catch (Exception e) {
				log.warn("公告实时推送失败 noticeId={}", notice.getId(), e);
			}
		};
		// 提交后再推，避免事务回滚后用户收到不存在的公告
		AfterCommit.execute(push);
	}

	/** 当前用户部门（优先取已激活的数据权限上下文，兜底查库） */
	private Long deptIdOf(Long userId) {
		DataScopeContext.Ctx ctx = DataScopeContext.current();
		if (ctx != null && ctx.deptId() != null) {
			return ctx.deptId();
		}
		SysUser user = userMapper.selectOneById(userId);
		return user == null ? null : user.getDeptId();
	}

	/** 装配当前用户对通知列表的已读标记 */
	private void fillReadFlag(List<SysNotice> notices) {
		if (notices.isEmpty()) {
			return;
		}
		Long userId = StpUtil.getLoginIdAsLong();
		List<Long> ids = notices.stream().map(SysNotice::getId).toList();
		List<Long> readIds = readMapper.selectListByQuery(
				QueryWrapper.create().eq("user_id", userId).in("notice_id", ids))
			.stream().map(SysNoticeRead::getNoticeId).toList();
		notices.forEach(n -> n.setReadFlag(readIds.contains(n.getId())));
	}

	/** 批量装配阅读人昵称/部门（避免 N+1） */
	private void fillReaders(List<SysNoticeRead> records) {
		if (records.isEmpty()) {
			return;
		}
		List<Long> userIds = records.stream().map(SysNoticeRead::getUserId).distinct().toList();
		Map<Long, SysUser> userMap = userMapper.selectListByIds(userIds).stream()
			.collect(Collectors.toMap(SysUser::getId, u -> u, (a, b) -> a));
		List<Long> deptIds = userMap.values().stream().map(SysUser::getDeptId)
			.filter(java.util.Objects::nonNull).distinct().toList();
		Map<Long, String> deptMap = deptIds.isEmpty() ? Map.of()
			: deptMapper.selectListByIds(deptIds).stream()
			.collect(Collectors.toMap(SysDept::getId, SysDept::getDeptName, (a, b) -> a));
		records.forEach(r -> {
			SysUser u = userMap.get(r.getUserId());
			if (u != null) {
				r.setNickname(u.getNickname() == null ? u.getUsername() : u.getNickname());
				r.setDeptName(u.getDeptId() == null ? null : deptMap.get(u.getDeptId()));
			}
		});
	}
}
