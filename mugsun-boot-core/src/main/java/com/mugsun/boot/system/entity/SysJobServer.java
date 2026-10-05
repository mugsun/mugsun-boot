package com.mugsun.boot.system.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

/**
 * 任务服务。应用口令只用于向该地址登记，接口不回传。
 */
@Table("sys_job_server")
public class SysJobServer extends BaseEntity {

	private String tenantId;
	private String serverName;
	private String serverUrl;
	private String appName;
	private String appPassword;
	private String remark;
	/** 1 已同步 / 0 未连上，空表示还没同步 */
	private Integer syncStatus;
	private String syncMsg;
	private LocalDateTime syncTime;

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getServerName() {
		return serverName;
	}

	public void setServerName(String serverName) {
		this.serverName = serverName;
	}

	public String getServerUrl() {
		return serverUrl;
	}

	public void setServerUrl(String serverUrl) {
		this.serverUrl = serverUrl;
	}

	public String getAppName() {
		return appName;
	}

	public void setAppName(String appName) {
		this.appName = appName;
	}

	public String getAppPassword() {
		return appPassword;
	}

	public void setAppPassword(String appPassword) {
		this.appPassword = appPassword;
	}

	public String getRemark() {
		return remark;
	}

	public void setRemark(String remark) {
		this.remark = remark;
	}

	public Integer getSyncStatus() {
		return syncStatus;
	}

	public void setSyncStatus(Integer syncStatus) {
		this.syncStatus = syncStatus;
	}

	public String getSyncMsg() {
		return syncMsg;
	}

	public void setSyncMsg(String syncMsg) {
		this.syncMsg = syncMsg;
	}

	public LocalDateTime getSyncTime() {
		return syncTime;
	}

	public void setSyncTime(LocalDateTime syncTime) {
		this.syncTime = syncTime;
	}
}
