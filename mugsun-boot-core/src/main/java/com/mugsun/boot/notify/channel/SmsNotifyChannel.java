package com.mugsun.boot.notify.channel;

import com.mugsun.boot.common.constant.NotifyConstants;
import com.mugsun.boot.notify.entity.SysNotifyChannel;
import com.mugsun.boot.notify.mapper.SysNotifyChannelMapper;
import com.mugsun.boot.system.service.SmsService;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Component;

/**
 * 短信渠道：委托 SmsService（复用 sys_sms 库表热配置，不手写厂商签名）。
 */
@Component
public class SmsNotifyChannel implements MessageChannel {

	private final SysNotifyChannelMapper channelMapper;
	private final SmsService smsService;

	public SmsNotifyChannel(SysNotifyChannelMapper channelMapper, SmsService smsService) {
		this.channelMapper = channelMapper;
		this.smsService = smsService;
	}

	@Override
	public String code() {
		return NotifyConstants.CHANNEL_SMS;
	}

	@Override
	public boolean enabled() {
		SysNotifyChannel cfg = channelMapper.selectOneByQuery(
			QueryWrapper.create().eq("channel", code()));
		return cfg != null && Integer.valueOf(1).equals(cfg.getStatus());
	}

	@Override
	public void send(NotifyMessage message) {
		String phone = message.getReceiver().getPhone();
		if (phone == null || phone.isBlank()) {
			throw new ServiceException("短信接收人缺少手机号");
		}
		smsService.sendText(phone, message.getContent());
	}
}
