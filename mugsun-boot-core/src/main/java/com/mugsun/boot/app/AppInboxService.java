package com.mugsun.boot.app;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.app.dto.AppMessageDetailVO;
import com.mugsun.boot.app.dto.AppMessageItemVO;
import com.mugsun.boot.app.dto.AppNoticeDetailVO;
import com.mugsun.boot.app.dto.AppNoticeItemVO;
import com.mugsun.boot.app.dto.AppPageVO;
import com.mugsun.boot.config.BizTables;
import com.mugsun.boot.gen.DbDialects;
import com.mugsun.boot.gen.RuntimeSql;
import com.mugsun.boot.gen.SqlDialect;
import com.mugsun.boot.message.entity.SysMessage;
import com.mugsun.boot.message.entity.SysMessageUser;
import com.mugsun.boot.message.mapper.SysMessageMapper;
import com.mugsun.boot.message.mapper.SysMessageUserMapper;
import com.mugsun.boot.system.entity.SysNotice;
import com.mugsun.boot.system.mapper.SysNoticeMapper;
import com.mugsun.boot.system.mapper.SysNoticeReadMapper;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AppInboxService {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private final SysMessageMapper messageMapper;
	private final SysMessageUserMapper messageUserMapper;
	private final SysNoticeMapper noticeMapper;
	private final SysNoticeReadMapper noticeReadMapper;
	private final AppNoticeSupport noticeSupport;

	public AppInboxService(SysMessageMapper messageMapper, SysMessageUserMapper messageUserMapper,
						   SysNoticeMapper noticeMapper, SysNoticeReadMapper noticeReadMapper,
						   AppNoticeSupport noticeSupport) {
		this.messageMapper = messageMapper;
		this.messageUserMapper = messageUserMapper;
		this.noticeMapper = noticeMapper;
		this.noticeReadMapper = noticeReadMapper;
		this.noticeSupport = noticeSupport;
	}

	public long messageUnread() {
		return messageUserMapper.selectCountByQuery(QueryWrapper.create()
			.eq("user_id", StpUtil.getLoginIdAsLong()).eq("is_read", 0));
	}

	public long noticeUnread() {
		return noticeSupport.unreadCount();
	}

	public AppPageVO<AppMessageItemVO> messages(long pageNum, long pageSize) {
		QueryWrapper query = QueryWrapper.create().eq("user_id", StpUtil.getLoginIdAsLong()).orderBy("id", false);
		Page<SysMessageUser> page = messageUserMapper.paginate(pageNum, Math.min(pageSize, 50), query);
		fillMessages(page.getRecords());
		List<AppMessageItemVO> records = page.getRecords().stream().map(r -> new AppMessageItemVO(
			r.getId(),
			r.getMessageId() == null ? 0L : r.getMessageId(),
			blank(r.getTitle(), "未命名消息"),
			fmt(r.getSendTime()),
			!Integer.valueOf(1).equals(r.getIsRead())
		)).toList();
		return new AppPageVO<>(records, page.getTotalRow());
	}

	public AppMessageDetailVO message(Long id) {
		SysMessageUser row = messageUserMapper.selectOneByQuery(QueryWrapper.create()
			.eq("id", id).eq("user_id", StpUtil.getLoginIdAsLong()));
		if (row == null) {
			throw new ServiceException("消息不存在");
		}
		fillMessages(List.of(row));
		return new AppMessageDetailVO(
			row.getId(),
			row.getMessageId() == null ? 0L : row.getMessageId(),
			blank(row.getTitle(), "未命名消息"),
			blank(row.getContent(), ""),
			fmt(row.getSendTime()),
			!Integer.valueOf(1).equals(row.getIsRead())
		);
	}

	public void readMessage(Long messageId) {
		SysMessageUser patch = new SysMessageUser();
		patch.setIsRead(1);
		patch.setReadTime(LocalDateTime.now());
		messageUserMapper.updateByQuery(patch, QueryWrapper.create()
			.eq("message_id", messageId).eq("user_id", StpUtil.getLoginIdAsLong()).eq("is_read", 0));
	}

	public AppPageVO<AppNoticeItemVO> notices(long pageNum, long pageSize) {
		QueryWrapper query = noticeSupport.visibleQuery();
		query.orderBy("is_top", false).orderBy("id", false);
		Page<SysNotice> page = noticeMapper.paginate(pageNum, Math.min(pageSize, 50), query);
		noticeSupport.fillReadFlag(page.getRecords());
		List<AppNoticeItemVO> records = page.getRecords().stream().map(n -> new AppNoticeItemVO(
			n.getId(),
			blank(n.getTitle(), "未命名公告"),
			fmt(n.getReleaseTime() != null ? n.getReleaseTime() : n.getCreateTime()),
			!Boolean.TRUE.equals(n.getReadFlag())
		)).toList();
		return new AppPageVO<>(records, page.getTotalRow());
	}

	public List<AppNoticeItemVO> recentNotices(int limit) {
		QueryWrapper query = noticeSupport.visibleQuery();
		query.orderBy("is_top", false).orderBy("id", false).limit(limit);
		List<SysNotice> list = noticeMapper.selectListByQuery(query);
		noticeSupport.fillReadFlag(list);
		return list.stream().map(n -> new AppNoticeItemVO(
			n.getId(),
			blank(n.getTitle(), "未命名公告"),
			fmt(n.getReleaseTime() != null ? n.getReleaseTime() : n.getCreateTime()),
			!Boolean.TRUE.equals(n.getReadFlag())
		)).toList();
	}

	public AppNoticeDetailVO notice(Long id) {
		SysNotice n = noticeSupport.requireVisible(id);
		noticeSupport.fillReadFlag(List.of(n));
		return new AppNoticeDetailVO(
			n.getId(),
			blank(n.getTitle(), "未命名公告"),
			blank(n.getContent(), ""),
			fmt(n.getReleaseTime() != null ? n.getReleaseTime() : n.getCreateTime()),
			!Boolean.TRUE.equals(n.getReadFlag())
		);
	}

	@Transactional(rollbackFor = Exception.class)
	public void readNotice(Long noticeId) {
		Long userId = StpUtil.getLoginIdAsLong();
		if (!noticeSupport.visibleToMe(noticeId)) {
			throw new ServiceException("通知不存在");
		}
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

	private void fillMessages(List<SysMessageUser> records) {
		List<Long> msgIds = records.stream().map(SysMessageUser::getMessageId).distinct().toList();
		if (msgIds.isEmpty()) {
			return;
		}
		Map<Long, SysMessage> map = messageMapper.selectListByIds(msgIds).stream()
			.collect(Collectors.toMap(SysMessage::getId, Function.identity()));
		records.forEach(r -> {
			SysMessage m = map.get(r.getMessageId());
			if (m != null) {
				r.setTitle(m.getTitle());
				r.setContent(m.getContent());
				r.setType(m.getType());
				r.setSendTime(m.getCreateTime());
			}
		});
	}

	private static String fmt(LocalDateTime t) {
		return t == null ? "" : TIME.format(t);
	}

	private static String blank(String v, String fallback) {
		return v == null || v.isBlank() ? fallback : v;
	}
}
