package com.mugsun.boot.auth.register;

/** 注册限流：由 {@link RegisterExceptionAdvice} 转成 HTTP 429 */
public class RegisterRateLimitException extends RuntimeException {
	public RegisterRateLimitException(String message) {
		super(message);
	}
}
