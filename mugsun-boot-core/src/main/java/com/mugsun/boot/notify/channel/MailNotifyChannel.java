package com.mugsun.boot.notify.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.constant.NotifyConstants;
import com.mugsun.boot.notify.entity.SysNotifyChannel;
import com.mugsun.boot.notify.mapper.SysNotifyChannelMapper;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * 邮件渠道：按 sys_notify_channel 配置懒建 JavaMailSender，配置指纹变化时重建（参照 OssService 差异刷新范式），
 * 密码凭据存 secret 列（SM4 加密存储）。config JSON 禁类名，键固定 host/port/username/from。
 */
@Component
public class MailNotifyChannel implements MessageChannel {

	private static final Logger log = LoggerFactory.getLogger(MailNotifyChannel.class);

	private final SysNotifyChannelMapper channelMapper;
	private final ObjectMapper objectMapper = new ObjectMapper();

	/** 懒建的邮件发送器 + 其配置指纹（host|port|username|secret），配置变更时重建 */
	private volatile JavaMailSenderImpl sender;
	private volatile String fingerprint;
	private volatile String from;

	public MailNotifyChannel(SysNotifyChannelMapper channelMapper) {
		this.channelMapper = channelMapper;
	}

	@Override
	public String code() {
		return NotifyConstants.CHANNEL_MAIL;
	}

	@Override
	public boolean enabled() {
		SysNotifyChannel cfg = configRow();
		return cfg != null && Integer.valueOf(1).equals(cfg.getStatus());
	}

	@Override
	public void send(NotifyMessage message) {
		String to = message.getReceiver().getEmail();
		if (to == null || to.isBlank()) {
			throw new ServiceException("邮件接收人缺少邮箱地址");
		}
		SysNotifyChannel cfg = configRow();
		if (cfg == null || !Integer.valueOf(1).equals(cfg.getStatus())) {
			throw new ServiceException("邮件渠道未启用或未配置");
		}
		JavaMailSenderImpl mailSender = senderOf(cfg);
		SimpleMailMessage mail = new SimpleMailMessage();
		mail.setFrom(from);
		mail.setTo(to);
		mail.setSubject(message.getTitle());
		mail.setText(message.getContent());
		mailSender.send(mail);
	}

	private SysNotifyChannel configRow() {
		return channelMapper.selectOneByQuery(QueryWrapper.create().eq("channel", code()));
	}

	/** 按配置行取发送器：指纹一致直接复用，否则 synchronized 重建（热更新即时生效） */
	private JavaMailSenderImpl senderOf(SysNotifyChannel cfg) {
		String fp = fingerprintOf(cfg);
		if (!fp.equals(fingerprint)) {
			synchronized (this) {
				if (!fp.equals(fingerprint)) {
					this.sender = build(cfg);
					this.fingerprint = fp;
					// 指纹为 host|port|username|secret 竖线分隔：只脱敏末段 secret，勿整串落明文密码
					log.info("邮件渠道发送器已按配置重建 fp={}", fp.replaceAll("\\|[^|]*$", "|***"));
				}
			}
		}
		return sender;
	}

	private String fingerprintOf(SysNotifyChannel cfg) {
		JsonNode json = configJson(cfg);
		return json.path("host").asText("") + "|" + json.path("port").asInt(25)
			+ "|" + json.path("username").asText("") + "|" + (cfg.getSecret() == null ? "" : cfg.getSecret());
	}

	private JavaMailSenderImpl build(SysNotifyChannel cfg) {
		JsonNode json = configJson(cfg);
		String host = json.path("host").asText("");
		if (host.isBlank()) {
			throw new ServiceException("邮件渠道配置缺少 host");
		}
		String username = json.path("username").asText("");
		this.from = json.path("from").asText(username.isBlank() ? "noreply@mugsun.com" : username);
		JavaMailSenderImpl impl = new JavaMailSenderImpl();
		impl.setHost(host);
		impl.setPort(json.path("port").asInt(25));
		Properties props = impl.getJavaMailProperties();
		props.put("mail.smtp.connectiontimeout", String.valueOf(NotifyConstants.MAIL_TIMEOUT_MS));
		props.put("mail.smtp.timeout", String.valueOf(NotifyConstants.MAIL_TIMEOUT_MS));
		// 有用户名才启用 SMTP 认证（空用户名视为匿名中继/测试 SMTP）
		if (!username.isBlank()) {
			impl.setUsername(username);
			impl.setPassword(cfg.getSecret());
			props.put("mail.smtp.auth", "true");
		}
		return impl;
	}

	private JsonNode configJson(SysNotifyChannel cfg) {
		try {
			String config = cfg.getConfig();
			return config == null || config.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(config);
		} catch (Exception e) {
			throw new ServiceException("邮件渠道配置 JSON 非法: " + e.getMessage());
		}
	}
}
