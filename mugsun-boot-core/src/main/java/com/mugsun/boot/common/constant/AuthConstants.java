package com.mugsun.boot.common.constant;

/**
 * 认证域常量：忘记密码（邮件验证码重置）链路的 Redis 键、TTL 与邮件模板码。
 */
public interface AuthConstants {

	/** 忘记密码验证码 Redis 键前缀（键后缀 租户:账号；6 位码，5min TTL，校验即焚） */
	String FORGET_CODE_PREFIX = "mugsun:forget:code:";
	/** 忘记密码发码节流键前缀（同账号 60s 一码，防邮件轰炸） */
	String FORGET_THROTTLE_PREFIX = "mugsun:forget:throttle:";
	/** 忘记密码错码计数键前缀（超限烧毁验证码，防在线爆破 6 位码） */
	String FORGET_ATTEMPT_PREFIX = "mugsun:forget:attempt:";

	/** 忘记密码验证码有效期（秒）：5 分钟 */
	long FORGET_CODE_EXPIRE_SECONDS = 300L;
	/** 忘记密码发码节流（秒）：同账号 60s 一码 */
	long FORGET_THROTTLE_SECONDS = 60L;
	/** 忘记密码错码上限：超限烧毁验证码，须重新发码 */
	int FORGET_MAX_ATTEMPTS = 5;

	/** 忘记密码邮件模板码（V61 种子，sys_mail_template） */
	String FORGET_MAIL_TEMPLATE = "forget_password";
}
