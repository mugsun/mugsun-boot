package com.mugsun.boot.app;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.config.BizTables;
import com.mugsun.boot.system.entity.SysNotice;
import com.mugsun.boot.system.entity.SysNoticeRead;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.mapper.SysNoticeMapper;
import com.mugsun.boot.system.mapper.SysNoticeReadMapper;
import com.mugsun.boot.system.mapper.SysNoticeScopeMapper;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 公告可见范围：与 SysNoticeController 同口径（管理员全量，其他人 all_visible 或范围表）。
 */
@Component
public class AppNoticeSupport {

	static final String PERM_MANAGE = "sys:notice:manage";

	private final SysNoticeMapper noticeMapper;
	private final SysNoticeReadMapper readMapper;
	private final SysNoticeScopeMapper scopeMapper;
	private final SysUserMapper userMapper;

	public AppNoticeSupport(SysNoticeMapper noticeMapper, SysNoticeReadMapper readMapper,
							SysNoticeScopeMapper scopeMapper, SysUserMapper userMapper) {
		this.noticeMapper = noticeMapper;
		this.readMapper = readMapper;
		this.scopeMapper = scopeMapper;
		this.userMapper = userMapper;
	}

	QueryWrapper visibleQuery() {
		QueryWrapper query = QueryWrapper.create();
		applyVisibleScope(query);
		return query;
	}

	void applyVisibleScope(QueryWrapper query) {
		if (StpUtil.hasPermission(PERM_MANAGE)) {
			return;
		}
		Long userId = StpUtil.getLoginIdAsLong();
		Long deptId = deptIdOf(userId);
		query.and("(all_visible = 1 or id in (select notice_id from "
			+ BizTables.of("sys_notice_scope")
			+ " where is_deleted = 0 and ((scope_type = 1 and scope_id = ?) or (scope_type = 2 and scope_id = ?))))",
			userId, deptId == null ? -1L : deptId);
	}

	boolean visibleToMe(Long noticeId) {
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

	long unreadCount() {
		Long userId = StpUtil.getLoginIdAsLong();
		QueryWrapper query = visibleQuery();
		query.and("id not in (select notice_id from " + BizTables.of("sys_notice_read")
			+ " where is_deleted = 0 and user_id = ?)", userId);
		return noticeMapper.selectCountByQuery(query);
	}

	void fillReadFlag(List<SysNotice> notices) {
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

	SysNotice requireVisible(Long noticeId) {
		if (!visibleToMe(noticeId)) {
			throw new ServiceException("通知不存在");
		}
		return noticeMapper.selectOneById(noticeId);
	}

	private Long deptIdOf(Long userId) {
		SysUser user = userMapper.selectOneById(userId);
		return user == null ? null : user.getDeptId();
	}
}
