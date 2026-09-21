package com.mugsun.boot.client.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 登录客户端差异化策略（平台级：验证码开关 / 并发在线数 / 令牌有效期，一 client 一套）
 */
@Table("sys_client")
public class SysClient extends BaseEntity {

	private String clientId;
	private String clientName;
	/** 图形验证码开关：1 开 / 0 关 */
	private Integer captchaEnabled;
	/** 单账号最大在线终端数（0=不限） */
	private Integer maxOnline;
	/** 令牌有效期（秒） */
	private Integer tokenTimeout;
	private Integer status;
	private String remark;

	public String getClientId() {
		return clientId;
	}

	public void setClientId(String clientId) {
		this.clientId = clientId;
	}

	public String getClientName() {
		return clientName;
	}

	public void setClientName(String clientName) {
		this.clientName = clientName;
	}

	public Integer getCaptchaEnabled() {
		return captchaEnabled;
	}

	public void setCaptchaEnabled(Integer captchaEnabled) {
		this.captchaEnabled = captchaEnabled;
	}

	public Integer getMaxOnline() {
		return maxOnline;
	}

	public void setMaxOnline(Integer maxOnline) {
		this.maxOnline = maxOnline;
	}

	public Integer getTokenTimeout() {
		return tokenTimeout;
	}

	public void setTokenTimeout(Integer tokenTimeout) {
		this.tokenTimeout = tokenTimeout;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}

	public String getRemark() {
		return remark;
	}

	public void setRemark(String remark) {
		this.remark = remark;
	}
}
