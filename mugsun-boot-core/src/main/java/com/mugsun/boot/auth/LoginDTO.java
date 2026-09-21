package com.mugsun.boot.auth;

/**
 * 登录参数
 */
public class LoginDTO {

	private String tenantId;
	private String username;
	private String password;
	private String captchaUuid;
	private String captchaCode;
	/** 登录客户端码（web/app…），决定验证码开关/令牌有效期/并发在线数等差异化策略；缺省 web */
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

	public String getCaptchaUuid() {
		return captchaUuid;
	}

	public void setCaptchaUuid(String captchaUuid) {
		this.captchaUuid = captchaUuid;
	}

	public String getCaptchaCode() {
		return captchaCode;
	}

	public void setCaptchaCode(String captchaCode) {
		this.captchaCode = captchaCode;
	}
}
