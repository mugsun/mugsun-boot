package com.mugsun.boot.auth;

/**
 * 自助注册参数
 */
public class RegisterDTO {

	private String username;
	private String password;
	private String nickname;
	private String phone;
	/** 图形验证码（防批量注册，与登录同机制） */
	private String captchaUuid;
	private String captchaCode;

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

	public String getNickname() {
		return nickname;
	}

	public void setNickname(String nickname) {
		this.nickname = nickname;
	}

	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phone = phone;
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
