package com.mugsun.boot.auth;

/**
 * 图形验证码返回：唯一标识 + Base64 图片 + 过期秒数（开发环境附明文便于联调）。
 */
public class CaptchaVO {

	private String captchaUuid;
	private String captchaImage;
	private Long expireSeconds;
	private String captchaCode;

	public String getCaptchaUuid() {
		return captchaUuid;
	}

	public void setCaptchaUuid(String captchaUuid) {
		this.captchaUuid = captchaUuid;
	}

	public String getCaptchaImage() {
		return captchaImage;
	}

	public void setCaptchaImage(String captchaImage) {
		this.captchaImage = captchaImage;
	}

	public Long getExpireSeconds() {
		return expireSeconds;
	}

	public void setExpireSeconds(Long expireSeconds) {
		this.expireSeconds = expireSeconds;
	}

	public String getCaptchaCode() {
		return captchaCode;
	}

	public void setCaptchaCode(String captchaCode) {
		this.captchaCode = captchaCode;
	}
}
