package com.mugsun.boot.captcha;

/**
 * 验证码业务码：HTTP 200 + R.code（与全局 ServiceException → R.fail 约定一致）。
 */
public final class CaptchaCodes {

	private CaptchaCodes() {
	}

	public static final int EXPIRED = 40010;
	public static final int FAILED = 40011;
	public static final int RATE_LIMITED = 40012;
	public static final int REQUIRED = 40013;
	public static final int DISABLED = 40014;

	static final String MSG_EXPIRED = "验证已过期，请重新验证";
	/** 失败原因一律不区分（防探测） */
	static final String MSG_FAILED = "验证未通过，请再试一次";
	static final String MSG_RATE_LIMITED = "尝试次数过多，请稍后再试";
	static final String MSG_REQUIRED = "请先完成安全验证";
	static final String MSG_DISABLED = "当前无需安全验证";
}
