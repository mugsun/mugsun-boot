package com.mugsun.boot.auth;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import com.mugsun.boot.mail.MailService;
import com.mugsun.boot.security.SecurityPolicyService;
import com.mugsun.core.tool.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;

/**
 * 双因子登录：密码校验通过后下发二次验证码（邮箱/短信，通道未配置/异常即显式报错），二次校验通过才发 token。
 */
@Service
public class TwoFactorService {

	private static final Logger log = LoggerFactory.getLogger(TwoFactorService.class);
	private static final String KEY = "mugsun:2fa:";
	private static final long EXPIRE_MIN = 5;
	/** 错码上限：超限即烧毁挑战（6 位码 5 分钟窗口防在线爆破） */
	private static final int MAX_ATTEMPTS = 5;

	private final StringRedisTemplate redis;
	private final SecurityPolicyService policy;
	private final MailService mailService;
	private final com.mugsun.boot.system.service.SmsService smsService;

	@Value("${mugsun.captcha.show-code:false}")
	private boolean showCode;

	public TwoFactorService(StringRedisTemplate redis, SecurityPolicyService policy, MailService mailService,
							com.mugsun.boot.system.service.SmsService smsService) {
		this.redis = redis;
		this.policy = policy;
		this.mailService = mailService;
		this.smsService = smsService;
	}

	public boolean isEnabled() {
		return policy.isTwoFactorEnabled();
	}

	/** 生成二次验证码存 Redis、按渠道下发，返回 [token, code(仅 dev)] */
	public String[] challenge(Long userId, String contact) {
		String code = RandomUtil.randomNumbers(6);
		String token = IdUtil.fastSimpleUUID();
		redis.opsForValue().set(KEY + token, userId + ":" + code + ":0", Duration.ofMinutes(EXPIRE_MIN));
		String channel = policy.getTwoFactorChannel();
		try {
			if ("sms".equals(channel)) {
				if (contact == null || contact.isBlank()) {
					throw new ServiceException("该账号未绑定手机号，无法下发双因子短信");
				}
				smsService.sendText(contact, "您的登录验证码是 " + code + "，5 分钟内有效");
			} else {
				if (contact == null || contact.isBlank()) {
					throw new ServiceException("该账号未绑定邮箱，无法下发双因子邮件");
				}
				mailService.sendByTemplate(contact, "login_2fa", Map.of("code", code));
			}
		} catch (ServiceException e) {
			// 通道显式降级错误（未配置/通道异常）如实上抛，仍烧毁挑战（防悬空验证码）
			redis.delete(KEY + token);
			throw e;
		} catch (Exception e) {
			// 非预期异常归一报错（勿降级日志暗送验证码，防全员锁死+日志泄码）
			redis.delete(KEY + token);
			throw new ServiceException("双因子验证码下发失败，请重试");
		}
		return new String[]{token, showCode ? code : null};
	}

	/** 校验二次验证码，返回 userId；错码累计达上限即烧毁挑战 */
	public Long verify(String token, String code) {
		if (token == null || token.isBlank() || code == null || code.isBlank()) {
			throw new ServiceException("请输入验证码");
		}
		String v = redis.opsForValue().get(KEY + token);
		if (v == null) {
			throw new ServiceException("验证码已过期，请重新登录");
		}
		int idx = v.lastIndexOf(':');
		int idx2 = v.lastIndexOf(':', idx - 1);
		String uid = v.substring(0, idx2);
		String real = v.substring(idx2 + 1, idx);
		int attempts = Integer.parseInt(v.substring(idx + 1));
		if (!real.equals(code)) {
			attempts++;
			if (attempts >= MAX_ATTEMPTS) {
				redis.delete(KEY + token);
				throw new ServiceException("错误次数过多，请重新登录");
			}
			// 保留剩余 TTL 回写错次
			Long ttl = redis.getExpire(KEY + token, java.util.concurrent.TimeUnit.SECONDS);
			redis.opsForValue().set(KEY + token, uid + ":" + real + ":" + attempts,
				Duration.ofSeconds(ttl == null || ttl < 0 ? 60 : ttl));
			throw new ServiceException("验证码错误");
		}
		redis.delete(KEY + token);
		return Long.parseLong(uid);
	}
}
