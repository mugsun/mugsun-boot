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
	/** keyboard 取题但租户未启用 SLIDER/ROTATE（无障碍方式） */
	public static final int KEYBOARD_UNAVAILABLE = 40015;
	/** pointer 取题但租户启用的题型全部不可用（如图标清单缺失时只启用了 ICON_CLICK） */
	public static final int TYPES_UNAVAILABLE = 40016;

	static final String MSG_EXPIRED = "验证已过期，请重新验证";
	/** 失败原因一律不区分（防探测） */
	static final String MSG_FAILED = "验证未通过，请再试一次";
	static final String MSG_RATE_LIMITED = "尝试次数过多，请稍后再试";
	static final String MSG_REQUIRED = "请先完成安全验证";
	static final String MSG_DISABLED = "当前无需安全验证";
	static final String MSG_KEYBOARD_UNAVAILABLE = "未启用无障碍验证方式";
	static final String MSG_TYPES_UNAVAILABLE = "验证方式暂不可用，请联系管理员";
}
