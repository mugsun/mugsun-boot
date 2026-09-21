package com.mugsun.boot.notify.api;

/**
 * 通知接收人：userId 为站内信投递依据；email/phone 为邮件/短信渠道联系方式（缺省该渠道记 IGNORE）。
 */
public class NotifyReceiver {

	private Long userId;
	private String name;
	private String email;
	private String phone;

	public NotifyReceiver() {
	}

	public NotifyReceiver(Long userId, String name, String email, String phone) {
		this.userId = userId;
		this.name = name;
		this.email = email;
		this.phone = phone;
	}

	public static NotifyReceiver of(Long userId, String name, String email, String phone) {
		return new NotifyReceiver(userId, name, email, phone);
	}

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phone = phone;
	}
}
