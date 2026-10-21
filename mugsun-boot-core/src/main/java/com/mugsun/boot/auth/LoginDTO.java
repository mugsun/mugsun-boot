package com.mugsun.boot.auth;

/**
 * 登录参数
 */
public class LoginDTO {

	private String tenantId;
	private String username;
	private String password;
	/** 行为验证码校验通过后签发的一次性令牌（POST /auth/captcha/verify） */
	private String captchaToken;
	/** 登录客户端码（web/app…），决定令牌有效期/并发在线数等差异化策略；缺省 web */
	private String clientId;

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getClientId() {
		return clientId;
	}

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getCaptchaToken() {
		return captchaToken;
	}

	public void setCaptchaToken(String captchaToken) {
		this.captchaToken = captchaToken;
	}
}
