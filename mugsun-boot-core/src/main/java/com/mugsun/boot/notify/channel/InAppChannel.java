package com.mugsun.boot.notify.channel;

import com.mugsun.boot.common.constant.NotifyConstants;
import com.mugsun.boot.message.MessageService;
import com.mugsun.boot.notify.entity.SysNotifyChannel;
import com.mugsun.boot.notify.mapper.SysNotifyChannelMapper;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 站内信渠道：委托消息中心 MessageService（保留其落库 + afterCommit WebSocket 实时推送）。
 */
@Component
public class InAppChannel implements MessageChannel {

	private final SysNotifyChannelMapper channelMapper;
	private final MessageService messageService;

	public InAppChannel(SysNotifyChannelMapper channelMapper, MessageService messageService) {
		this.channelMapper = channelMapper;
		this.messageService = messageService;
	}

	@Override
	public String code() {
		return NotifyConstants.CHANNEL_IN_APP;
	}

	@Override
	public boolean enabled() {
		SysNotifyChannel cfg = channelMapper.selectOneByQuery(
			QueryWrapper.create().eq("channel", code()));
		return cfg != null && Integer.valueOf(1).equals(cfg.getStatus());
	}

	@Override
	public void send(NotifyMessage message) {
		if (message.getReceiver().getUserId() == null) {
			throw new ServiceException("站内信接收人缺少用户 id");
		}
		// type 传 null 由 MessageService 落默认 system（与手工发送一致）
		messageService.sendSystem(message.getTitle(), message.getContent(), null,
			List.of(message.getReceiver().getUserId()));
	}
}
