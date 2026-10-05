package com.mugsun.boot.system.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 密钥调用记录。params 只保留访问标识和「密钥已隐藏」，不存 SecretKey。
 */
@Table("sys_api_key_log")
public class SysApiKeyLog extends BaseEntity {

	private String tenantId;
	private Long apiKeyId;
	private String accessKey;
	private String keyName;
	private String requestUri;
	private String method;
	/** 1 通过 / 0 拒绝 */
	private Integer status;
	private String remoteIp;
	private String userAgent;
	private String params;
	private Integer costMs;
	private String msg;

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public Long getApiKeyId() {
		return apiKeyId;
	}

	public void setApiKeyId(Long apiKeyId) {
		this.apiKeyId = apiKeyId;
	}

	public String getAccessKey() {
		return accessKey;
	}

	public void setAccessKey(String accessKey) {
		this.accessKey = accessKey;
	}

	public String getKeyName() {
		return keyName;
	}

	public void setKeyName(String keyName) {
		this.keyName = keyName;
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

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
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

	public String getParams() {
		return params;
	}

	public void setParams(String params) {
		this.params = params;
	}

	public Integer getCostMs() {
		return costMs;
	}

	public void setCostMs(Integer costMs) {
		this.costMs = costMs;
	}

	public String getMsg() {
		return msg;
	}

	public void setMsg(String msg) {
		this.msg = msg;
	}
}
