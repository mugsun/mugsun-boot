package com.mugsun.boot.message;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.message.entity.SysMessage;
import com.mugsun.boot.message.entity.SysMessageUser;
import com.mugsun.boot.message.mapper.SysMessageMapper;
import com.mugsun.boot.message.mapper.SysMessageUserMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 站内信：管理员按模板占位发给指定收件人；收件人查阅、未读统计与标记已读。
 */
@RestController
@RequestMapping("/system/message")
@SaCheckLogin
public class MessageController {

	private final SysMessageMapper messageMapper;
	private final SysMessageUserMapper messageUserMapper;
	private final MessageService messageService;

	public MessageController(SysMessageMapper messageMapper, SysMessageUserMapper messageUserMapper,
							 MessageService messageService) {
		this.messageMapper = messageMapper;
		this.messageUserMapper = messageUserMapper;
		this.messageService = messageService;
	}

	/** 发送站内信：可选模板 + 占位替换 → 建消息主体 + 逐收件人未读记录（落库后实时推送在线收件人） */
	@PostMapping("/send")
	@SaCheckPermission("sys:message:manage")
	@RepeatSubmit
	public R<Void> send(@RequestBody MessageSendDTO dto) {
		if (dto == null) {
			throw new ServiceException("请先选择收件人。可以按用户名或昵称搜索");
		}
		messageService.send(dto);
		int count = dto.getRecipientIds().stream().filter(java.util.Objects::nonNull).distinct().toList().size();
		return R.success("已发送给 " + count + " 人。对方可以在我的消息里看到");
	}

	/** 当前用户未读数（顶栏角标） */
	@GetMapping("/unread-count")
	public R<Long> unreadCount() {
		return R.data(messageUserMapper.selectCountByQuery(QueryWrapper.create()
			.eq("user_id", StpUtil.getLoginIdAsLong()).eq("is_read", 0)));
	}

	/** 顶栏下拉：最近若干条 */
	@GetMapping("/recent")
	public R<List<SysMessageUser>> recent(@RequestParam(defaultValue = "8") int limit) {
		int size = Math.min(Math.max(limit, 1), 20);
		List<SysMessageUser> list = messageUserMapper.selectListByQuery(QueryWrapper.create()
			.eq("user_id", StpUtil.getLoginIdAsLong()).orderBy("id", false).limit(size));
		fillMessages(list);
		return R.data(list);
	}

	/** 我的消息分页 */
	@GetMapping("/my/page")
	public R<Page<SysMessageUser>> myPage(@RequestParam(defaultValue = "1") long pageNum,
										  @RequestParam(defaultValue = "10") long pageSize,
										  @RequestParam(required = false) Integer isRead,
										  @RequestParam(required = false) String name) {
		if (pageNum < 1 || pageSize < 1 || pageSize > 100) {
			throw new ServiceException("页码从 1 开始，每页最多 100 条");
		}
		if (isRead != null && isRead != 0 && isRead != 1) {
			throw new ServiceException("状态请选择未读或已读");
		}
		QueryWrapper query = QueryWrapper.create().eq("user_id", StpUtil.getLoginIdAsLong()).orderBy("id", false);
		if (isRead != null) {
			query.eq("is_read", isRead);
		}
		if (name != null && !name.isBlank()) {
			List<Long> msgIds = messageMapper.selectListByQuery(QueryWrapper.create().like("title", name.trim()))
				.stream().map(SysMessage::getId).toList();
			if (msgIds.isEmpty()) {
				return R.data(new Page<>(List.of(), pageNum, pageSize, 0));
			}
			query.in("message_id", msgIds);
		}
		Page<SysMessageUser> page = messageUserMapper.paginate(pageNum, pageSize, query);
		fillMessages(page.getRecords());
		return R.data(page);
	}

	/** 标记单条已读（按消息 id + 当前用户） */
	@PostMapping("/read/{messageId}")
	public R<Void> read(@PathVariable Long messageId) {
		Long userId = StpUtil.getLoginIdAsLong();
		SysMessageUser mine = messageUserMapper.selectOneByQuery(QueryWrapper.create()
			.eq("message_id", messageId).eq("user_id", userId));
		if (mine == null) {
			throw new ServiceException("这条消息不存在，或不是你的。请刷新列表后再看");
		}
		if (Integer.valueOf(1).equals(mine.getIsRead())) {
			throw new ServiceException("这条消息已经是已读");
		}
		mine.setIsRead(1);
		mine.setReadTime(LocalDateTime.now());
		messageUserMapper.update(mine);
		return R.success("已标为已读。未读角标会跟着减少");
	}

	/** 全部标记已读 */
	@PostMapping("/read-all")
	public R<Void> readAll() {
		Long userId = StpUtil.getLoginIdAsLong();
		long unread = messageUserMapper.selectCountByQuery(QueryWrapper.create()
			.eq("user_id", userId).eq("is_read", 0));
		if (unread == 0) {
			throw new ServiceException("没有未读消息。收到新消息后再来标");
		}
		SysMessageUser patch = new SysMessageUser();
		patch.setIsRead(1);
		patch.setReadTime(LocalDateTime.now());
		messageUserMapper.updateByQuery(patch, QueryWrapper.create()
			.eq("user_id", userId).eq("is_read", 0));
		return R.success("已全部标为已读。未读角标会清掉");
	}

	/** 收件人删除自己的消息记录 */
	@PostMapping("/remove")
	@RepeatSubmit
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的消息");
		}
		List<Long> distinct = new ArrayList<>();
		for (Long id : ids) {
			if (id == null) {
				throw new ServiceException("有消息不存在。请刷新列表后再删除");
			}
			if (!distinct.contains(id)) {
				distinct.add(id);
			}
		}
		Long userId = StpUtil.getLoginIdAsLong();
		List<SysMessageUser> rows = messageUserMapper.selectListByQuery(QueryWrapper.create()
			.in("id", distinct).eq("user_id", userId));
		if (rows.size() != distinct.size()) {
			throw new ServiceException("有消息不存在，或不是你的。请刷新列表后再删");
		}
		messageUserMapper.deleteByQuery(QueryWrapper.create().in("id", distinct).eq("user_id", userId));
		return R.success("已删除 " + rows.size() + " 条消息。收件箱里不会再出现");
	}

	/** 装配收件记录所属消息的标题/内容/类型/发送时间 */
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
}
