package com.mugsun.boot.auth;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.captcha.generator.RandomGenerator;
import cn.hutool.core.util.IdUtil;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.time.Duration;

/**
 * 图形验证码：Hutool LineCaptcha 生成，答案入 Redis（TTL 可配，默认 120s，校验后即焚）。
 * 依赖 G34 接入的 StringRedisTemplate，登录前置校验。
 */
@Service
public class CaptchaService {

	private static final String KEY_PREFIX = "mugsun:captcha:";

	private final StringRedisTemplate redisTemplate;

	/** 是否启用登录验证码 */
	@Value("${mugsun.captcha.enabled:true}")
	private boolean enabled;
	/** 是否随响应返回明文（开发联调用，生产须关） */
	@Value("${mugsun.captcha.show-code:true}")
	private boolean showCode;
	/** 有效期（秒），默认 120 */
	@Value("${mugsun.captcha.expire-seconds:120}")
	private long expireSeconds;

	public CaptchaService(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	public boolean isEnabled() {
		return enabled;
	}

	/** 生成验证码：答案写入 Redis，返回 uuid + Base64 图片 */
	public CaptchaVO generate() {
		LineCaptcha captcha = CaptchaUtil.createLineCaptcha(125, 43, 4, 80);
		captcha.setGenerator(new RandomGenerator("0123456789", 4));
		captcha.setBackground(new Color(230, 244, 255));
		captcha.createCode();
		String code = captcha.getCode();
		String uuid = IdUtil.fastSimpleUUID();
		redisTemplate.opsForValue().set(KEY_PREFIX + uuid, code, Duration.ofSeconds(expireSeconds));

		CaptchaVO vo = new CaptchaVO();
		vo.setCaptchaUuid(uuid);
		vo.setCaptchaImage("data:image/png;base64," + captcha.getImageBase64());
		vo.setExpireSeconds(expireSeconds);
		if (showCode) {
			vo.setCaptchaCode(code);
		}
		return vo;
	}

	/** 校验验证码：过期/错误抛异常，命中即从 Redis 删除防重放 */
	public void verify(String uuid, String code) {
		if (!enabled) {
			return;
		}
		if (uuid == null || uuid.isBlank() || code == null || code.isBlank()) {
			throw new ServiceException("请输入验证码");
		}
		String key = KEY_PREFIX + uuid;
		String cached = redisTemplate.opsForValue().get(key);
		if (cached == null) {
			throw new ServiceException("验证码已过期，请刷新重试");
		}
		redisTemplate.delete(key);
		if (!cached.equalsIgnoreCase(code)) {
			throw new ServiceException("验证码错误");
		}
	}
}
