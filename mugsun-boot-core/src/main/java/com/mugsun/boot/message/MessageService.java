package com.mugsun.boot.message;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.WsConstants;
import com.mugsun.boot.common.tx.AfterCommit;
import com.mugsun.boot.message.entity.SysMessage;
import com.mugsun.boot.message.entity.SysMessageTemplate;
import com.mugsun.boot.message.entity.SysMessageUser;
import com.mugsun.boot.message.mapper.SysMessageMapper;
import com.mugsun.boot.message.mapper.SysMessageTemplateMapper;
import com.mugsun.boot.message.mapper.SysMessageUserMapper;
import com.mugsun.boot.notify.NotifyTemplateRenderer;
import com.mugsun.boot.websocket.WsFrame;
import com.mugsun.boot.websocket.WsMessageSender;
import com.mugsun.core.tool.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内信服务：发送（模板渲染 + 消息主体 + 逐收件人未读记录），落库成功后向在线收件人实时推送。
 * <p>渲染收编统一渲染器 {@link NotifyTemplateRenderer}（缺参 fail-fast，取代原"未提供占位静默清除"）。
 */
@Service
public class MessageService {

	private static final Logger log = LoggerFactory.getLogger(MessageService.class);

	private final SysMessageMapper messageMapper;
	private final SysMessageUserMapper messageUserMapper;
	private final SysMessageTemplateMapper templateMapper;
	private final com.mugsun.boot.system.mapper.SysUserMapper userMapper;
	private final WsMessageSender wsMessageSender;
	private final NotifyTemplateRenderer renderer;

	public MessageService(SysMessageMapper messageMapper, SysMessageUserMapper messageUserMapper,
						  SysMessageTemplateMapper templateMapper, WsMessageSender wsMessageSender,
						  NotifyTemplateRenderer renderer,
						  com.mugsun.boot.system.mapper.SysUserMapper userMapper) {
		this.messageMapper = messageMapper;
		this.messageUserMapper = messageUserMapper;
		this.templateMapper = templateMapper;
		this.wsMessageSender = wsMessageSender;
		this.renderer = renderer;
		this.userMapper = userMapper;
	}

	/** 发送站内信：可选模板 + 占位替换 → 建消息主体 + 逐收件人未读记录 → 实时推送在线收件人 */
	public void send(MessageSendDTO dto) {
		// 收件人归属校验：仅允许发往本租户用户（防跨租户钓鱼投递 + 跨租户 WS 实时弹窗）
		assertRecipientsInTenant(dto.getRecipientIds());
		doSend(dto, StpUtil.getLoginIdAsLong());
	}

	/** 收件人必须全部属于当前租户（Flex 租户条件自动过滤，count 不符即含越界/不存在收件人） */
	private void assertRecipientsInTenant(List<Long> recipientIds) {
		if (recipientIds == null || recipientIds.isEmpty()) {
			return;
		}
		List<Long> ids = recipientIds.stream().distinct().toList();
		long count = userMapper.selectCountByQuery(
			com.mybatisflex.core.query.QueryWrapper.create().in("id", ids));
		if (count != ids.size()) {
			throw new ServiceException("收件人包含不存在或不属于本租户的用户");
		}
	}

	/** 系统通知发送（统一调度站内信渠道用）：异步线程无登录会话，发送人置空；
	 *  内容已经统一调度侧渲染完毕，此处直发不再二次渲染（防参数值含 ${} 字面量时误抽占位致投递失败） */
	public void sendSystem(String title, String content, String type, List<Long> recipientIds) {
		MessageSendDTO dto = new MessageSendDTO();
		dto.setTitle(title);
		dto.setContent(content);
		dto.setType(type);
		dto.setRecipientIds(recipientIds);
		doSend(dto, null, true);
	}

	private void doSend(MessageSendDTO dto, Long senderId) {
		doSend(dto, senderId, false);
	}

	private void doSend(MessageSendDTO dto, Long senderId, boolean skipRender) {
		if (dto.getRecipientIds() == null || dto.getRecipientIds().isEmpty()) {
			throw new ServiceException("请选择收件人");
		}
		String title = dto.getTitle();
		String content = dto.getContent();
		if (dto.getTemplateId() != null) {
			SysMessageTemplate tpl = templateMapper.selectOneById(dto.getTemplateId());
			if (tpl != null) {
				title = tpl.getTitle();
				content = tpl.getContent();
			}
		}
		if (!skipRender) {
			title = renderer.render(title, dto.getParams());
			content = renderer.render(content, dto.getParams());
		}
		if (title == null || title.isBlank()) {
			throw new ServiceException("消息标题不能为空");
		}
		SysMessage message = new SysMessage();
		message.setTitle(title);
		message.setContent(content);
		message.setType(dto.getType() == null || dto.getType().isBlank() ? "system" : dto.getType());
		message.setSenderId(senderId);
		messageMapper.insertSelective(message);
		List<Long> recipientIds = dto.getRecipientIds().stream().distinct().toList();
		recipientIds.forEach(uid -> {
			SysMessageUser mu = new SysMessageUser();
			mu.setMessageId(message.getId());
			mu.setUserId(uid);
			mu.setIsRead(0);
			messageUserMapper.insertSelective(mu);
		});
		pushNewMessage(message, recipientIds);
	}

	/** 实时推送新站内信：若处于事务中则提交后再推，推送失败不影响主流程 */
	private void pushNewMessage(SysMessage message, List<Long> recipientIds) {
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("messageId", message.getId());
		content.put("title", message.getTitle());
		content.put("content", message.getContent());
		content.put("type", message.getType());
		// createTime 由数据库 now() 填充且不回写实体，推送帧取应用侧当前时间（与落库时刻同秒级一致）
		content.put("sendTime", LocalDateTime.now());
		WsFrame frame = WsFrame.of(WsConstants.MESSAGE_NEW, content);
		AfterCommit.execute(() -> {
			try {
				wsMessageSender.sendToUsers(recipientIds, frame);
			} catch (Exception e) {
				log.warn("站内信实时推送失败 messageId={}", message.getId(), e);
			}
		});
	}
}
