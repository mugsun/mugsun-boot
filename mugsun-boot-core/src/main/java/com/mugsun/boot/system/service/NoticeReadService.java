package com.mugsun.boot.system.service;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.config.BizTables;
import com.mugsun.boot.gen.DbDialects;
import com.mugsun.boot.gen.RuntimeSql;
import com.mugsun.boot.gen.SqlDialect;
import com.mugsun.boot.system.entity.SysNotice;
import com.mugsun.boot.system.entity.SysNoticeRead;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.mapper.SysNoticeMapper;
import com.mugsun.boot.system.mapper.SysNoticeReadMapper;
import com.mugsun.boot.system.mapper.SysNoticeScopeMapper;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 按「谁在看」读公告：分页、最近几条、详情、未读数、标记已读、按标题找。
 *
 * <p>可见范围与 SysNoticeController 同口径：有公告管理权限的看全部，其他人看全员可见的，和点名给本人或本部门的。
 * 这里不读登录态，看的人由参数给，所以手机端、全局搜索和微服务版的内部接口用的是同一份。
 */
@Service
public class NoticeReadService implements NoticeTitleFinder {

	/** 不存在和看不到是同一个提示，免得有人挨个试编号 */
	private static final String NOT_FOUND = "通知不存在";

	private final SysNoticeMapper noticeMapper;
	private final SysNoticeReadMapper readMapper;
	private final SysNoticeScopeMapper scopeMapper;
	private final SysUserMapper userMapper;

	public NoticeReadService(SysNoticeMapper noticeMapper, SysNoticeReadMapper readMapper,
							 SysNoticeScopeMapper scopeMapper, SysUserMapper userMapper) {
		this.noticeMapper = noticeMapper;
		this.readMapper = readMapper;
		this.scopeMapper = scopeMapper;
		this.userMapper = userMapper;
	}

	/** 看得到的公告分页：置顶在前、新的在前，带上这个人读没读过 */
	public Page<SysNotice> page(NoticeViewer viewer, long pageNum, long pageSize) {
		QueryWrapper query = visibleQuery(viewer);
		query.orderBy("is_top", false).orderBy("id", false);
		Page<SysNotice> page = noticeMapper.paginate(pageNum, pageSize, query);
		fillReadFlag(viewer, page.getRecords());
		return page;
	}

	/** 看得到的公告里最近的几条，排序和 {@link #page} 一样 */
	public List<SysNotice> recent(NoticeViewer viewer, int limit) {
		QueryWrapper query = visibleQuery(viewer);
		query.orderBy("is_top", false).orderBy("id", false).limit(limit);
		List<SysNotice> list = noticeMapper.selectListByQuery(query);
		fillReadFlag(viewer, list);
		return list;
	}

	/** 一条公告，带上这个人读没读过 */
	public SysNotice detail(NoticeViewer viewer, Long noticeId) {
		if (!visible(viewer, noticeId)) {
			throw new ServiceException(NOT_FOUND);
		}
		SysNotice notice = noticeMapper.selectOneById(noticeId);
		fillReadFlag(viewer, List.of(notice));
		return notice;
	}

	/** 看得到、还没读过的条数 */
	public long unreadCount(NoticeViewer viewer) {
		QueryWrapper query = visibleQuery(viewer);
		query.and("id not in (select notice_id from " + BizTables.of("sys_notice_read")
			+ " where is_deleted = 0 and user_id = ?)", viewer.userId());
		return noticeMapper.selectCountByQuery(query);
	}

	/** 标记已读，重复标记不出错：第一次读，阅读人数和阅读次数各加一；以后每次只加阅读次数 */
	@Transactional(rollbackFor = Exception.class)
	public void markRead(NoticeViewer viewer, Long noticeId) {
		if (!visible(viewer, noticeId)) {
			throw new ServiceException(NOT_FOUND);
		}
		long userId = viewer.userId();
		// PG：原子 upsert + xmax 判首读；Oracle/达梦无 xmax，应用层先查后写
		boolean firstRead;
		SqlDialect d = DbDialects.current();
		if (d.oracleFamily()) {
			Row existed = Db.selectOneBySql(
				"select id from " + BizTables.of("sys_notice_read")
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
		Db.updateBySql("update " + BizTables.of("sys_notice")
			+ " set view_pv = view_pv + 1" + (firstRead ? ", view_uv = view_uv + 1" : "")
			+ " where id = ?", noticeId);
	}

	@Override
	public List<String> titlesLike(NoticeViewer viewer, String keyword, int limit) {
		// 词是空的时候 like 条件会被框架丢掉，变成把看得到的全列出来，所以先挡住
		if (keyword == null || keyword.isBlank()) {
			return List.of();
		}
		QueryWrapper query = QueryWrapper.create().like("title", keyword).orderBy("id", false).limit(limit);
		applyVisibleScope(viewer, query);
		return noticeMapper.selectListByQuery(query).stream().map(SysNotice::getTitle).toList();
	}

	/** 这个人看不看得到这条公告；公告不存在也算看不到 */
	public boolean visible(NoticeViewer viewer, Long noticeId) {
		if (noticeId == null) {
			return false;
		}
		SysNotice notice = noticeMapper.selectOneById(noticeId);
		if (notice == null) {
			return false;
		}
		if (viewer.manage()) {
			return true;
		}
		if (Integer.valueOf(1).equals(notice.getAllVisible())) {
			return true;
		}
		Long deptId = deptIdOf(viewer.userId());
		return scopeMapper.selectCountByQuery(QueryWrapper.create().eq("notice_id", noticeId)
			.and("((scope_type = 1 and scope_id = ?) or (scope_type = 2 and scope_id = ?))",
				viewer.userId(), deptId == null ? -1L : deptId)) > 0;
	}

	private QueryWrapper visibleQuery(NoticeViewer viewer) {
		QueryWrapper query = QueryWrapper.create();
		applyVisibleScope(viewer, query);
		return query;
	}

	private void applyVisibleScope(NoticeViewer viewer, QueryWrapper query) {
		if (viewer.manage()) {
			return;
		}
		Long deptId = deptIdOf(viewer.userId());
		query.and("(all_visible = 1 or id in (select notice_id from "
			+ BizTables.of("sys_notice_scope")
			+ " where is_deleted = 0 and ((scope_type = 1 and scope_id = ?) or (scope_type = 2 and scope_id = ?))))",
			viewer.userId(), deptId == null ? -1L : deptId);
	}

	private void fillReadFlag(NoticeViewer viewer, List<SysNotice> notices) {
		if (notices.isEmpty()) {
			return;
		}
		List<Long> ids = notices.stream().map(SysNotice::getId).toList();
		List<Long> readIds = readMapper.selectListByQuery(
				QueryWrapper.create().eq("user_id", viewer.userId()).in("notice_id", ids))
			.stream().map(SysNoticeRead::getNoticeId).toList();
		notices.forEach(n -> n.setReadFlag(readIds.contains(n.getId())));
	}

	private Long deptIdOf(long userId) {
		SysUser user = userMapper.selectOneById(userId);
		return user == null ? null : user.getDeptId();
	}
}
