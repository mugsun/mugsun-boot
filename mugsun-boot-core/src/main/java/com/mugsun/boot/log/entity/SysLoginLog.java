package com.mugsun.boot.log.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

/**
 * 登录日志
 */
@Table("sys_login_log")
public class SysLoginLog extends BaseEntity {

	private String username;
	private String ip;
	private Integer status;
	private String msg;
	private LocalDateTime loginTime;
	/** 登录用户所属租户（登录前无租户上下文，显式记录） */
	private String tenantId;
	/** 浏览器 UA */
	private String userAgent;
	/** 浏览器（写入时 UA 解析落列，历史行可为空） */
	private String browser;
	/** 操作系统（写入时 UA 解析落列，历史行可为空） */
	private String os;
	/** IP 归属地（ip2region 离线解析，开关关闭/内网/未命中为空） */
	private String loginLocation;
	/** 登录客户端码（web/app…） */
	private String device;
	/** 账号当前是否处于登录失败锁定中（非表列，page 接口按 Redis 锁键富化） */
	@com.mybatisflex.annotation.Column(ignore = true)
	private Boolean locked;

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getIp() {
		return ip;
	}

	public void setIp(String ip) {
		this.ip = ip;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}

	public String getMsg() {
		return msg;
	}

	public void setMsg(String msg) {
		this.msg = msg;
	}

	public LocalDateTime getLoginTime() {
		return loginTime;
	}

	public void setLoginTime(LocalDateTime loginTime) {
		this.loginTime = loginTime;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public void setUserAgent(String userAgent) {
		this.userAgent = userAgent;
	}

	public String getBrowser() {
		return browser;
	}

	public void setBrowser(String browser) {
		this.browser = browser;
	}

	public String getOs() {
		return os;
	}

	public void setOs(String os) {
		this.os = os;
	}

	public String getLoginLocation() {
		return loginLocation;
	}

	public void setLoginLocation(String loginLocation) {
		this.loginLocation = loginLocation;
	}

	public String getDevice() {
		return device;
	}

	public void setDevice(String device) {
		this.device = device;
	}

	public Boolean getLocked() {
		return locked;
	}

	public void setLocked(Boolean locked) {
		this.locked = locked;
	}
}
