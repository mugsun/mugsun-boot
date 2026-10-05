package com.mugsun.boot.log.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 通用日志。业务编号标识一次业务动作，内容里不应出现口令明文。
 */
@Table("sys_usual_log")
public class SysUsualLog extends BaseEntity {

	private String tenantId;
	/** info / debug / warn / error */
	private String logLevel;
	private String logId;
	private String logData;
	private String requestUri;
	private String method;
	private String remoteIp;
	private String userAgent;

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getLogLevel() {
		return logLevel;
	}

	public void setLogLevel(String logLevel) {
		this.logLevel = logLevel;
	}

	public String getLogId() {
		return logId;
	}

	public void setLogId(String logId) {
		this.logId = logId;
	}

	public String getLogData() {
		return logData;
	}

	public void setLogData(String logData) {
		this.logData = logData;
	}

	public String getRequestUri() {
		return requestUri;
	}

	public void setRequestUri(String requestUri) {
		this.requestUri = requestUri;
	}

	public String getMethod() {
		return method;
	}

	public void setMethod(String method) {
		this.method = method;
	}

	public String getRemoteIp() {
		return remoteIp;
	}

	public void setRemoteIp(String remoteIp) {
		this.remoteIp = remoteIp;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public void setUserAgent(String userAgent) {
		this.userAgent = userAgent;
	}
}
