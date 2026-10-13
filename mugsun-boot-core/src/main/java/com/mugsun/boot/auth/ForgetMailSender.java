package com.mugsun.boot.auth;

import com.mugsun.boot.common.constant.AuthConstants;
import com.mugsun.boot.mail.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 忘记密码邮件异步下发：调用方立即返回，失败只焚码 + 记日志，不影响对外响应 */
@Component
public class ForgetMailSender {

	private static final Logger log = LoggerFactory.getLogger(ForgetMailSender.class);

	private final MailService mailService;
	private final StringRedisTemplate redis;

	public ForgetMailSender(MailService mailService, StringRedisTemplate redis) {
		this.mailService = mailService;
		this.redis = redis;
	}

	@Async
	public void send(String email, String code, String codeKey, String tenantId, String username) {
		try {
			mailService.sendByTemplate(email, AuthConstants.FORGET_MAIL_TEMPLATE, Map.of("code", code));
			log.info("忘记密码验证码已下发 tenant={} username={}", tenantId, username);
		} catch (Exception e) {
			// 下发失败即焚码（不留「未送达却可重置」的悬空码）
			redis.delete(codeKey);
			log.warn("忘记密码验证码下发失败 tenant={} username={}: {}", tenantId, username, e.toString());
		}
	}
}
