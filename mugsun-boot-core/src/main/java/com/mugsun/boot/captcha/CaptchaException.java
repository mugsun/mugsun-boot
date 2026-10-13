package com.mugsun.boot.captcha;

import com.mugsun.core.tool.exception.ServiceException;

import java.util.Map;

/** 携带 4001x 业务码与可选 data（如 retryAfter）的验证码异常 */
public class CaptchaException extends ServiceException {

	private final int code;
	private final transient Map<String, Object> data;

	public CaptchaException(int code, String message, Map<String, Object> data) {
		super(message);
		this.code = code;
		this.data = data;
	}

	public int getCode() {
		return code;
	}

	public Map<String, Object> getData() {
		return data;
	}

	public static CaptchaException expired() {
		return new CaptchaException(CaptchaCodes.EXPIRED, CaptchaCodes.MSG_EXPIRED, null);
	}

	public static CaptchaException failed() {
		return new CaptchaException(CaptchaCodes.FAILED, CaptchaCodes.MSG_FAILED, null);
	}

	public static CaptchaException rateLimited(long retryAfterSeconds) {
		return new CaptchaException(CaptchaCodes.RATE_LIMITED, CaptchaCodes.MSG_RATE_LIMITED,
			Map.of("retryAfter", Math.max(1L, retryAfterSeconds)));
	}

	public static CaptchaException required() {
		return new CaptchaException(CaptchaCodes.REQUIRED, CaptchaCodes.MSG_REQUIRED, null);
	}

	public static CaptchaException keyboardUnavailable() {
		return new CaptchaException(CaptchaCodes.KEYBOARD_UNAVAILABLE, CaptchaCodes.MSG_KEYBOARD_UNAVAILABLE, null);
	}

	public static CaptchaException disabled() {
		return new CaptchaException(CaptchaCodes.DISABLED, CaptchaCodes.MSG_DISABLED, null);
	}
}
