package com.mugsun.boot.app;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.app.dto.AppMessageDetailVO;
import com.mugsun.boot.app.dto.AppMessageItemVO;
import com.mugsun.boot.app.dto.AppPageVO;
import com.mugsun.boot.message.entity.SysMessage;
import com.mugsun.boot.message.entity.SysMessageUser;
import com.mugsun.boot.message.mapper.SysMessageMapper;
import com.mugsun.boot.message.mapper.SysMessageUserMapper;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 手机端的站内信：列表、详情、标记已读和未读数。公告在 {@link AppNoticeService}。
 */
@Service
public class AppInboxService {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private final SysMessageMapper messageMapper;
	private final SysMessageUserMapper messageUserMapper;

	public AppInboxService(SysMessageMapper messageMapper, SysMessageUserMapper messageUserMapper) {
		this.messageMapper = messageMapper;
		this.messageUserMapper = messageUserMapper;
	}

	public long messageUnread() {
		return messageUserMapper.selectCountByQuery(QueryWrapper.create()
			.eq("user_id", StpUtil.getLoginIdAsLong()).eq("is_read", 0));
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
