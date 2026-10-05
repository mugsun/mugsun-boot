package com.mugsun.boot.system.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.mugsun.boot.common.crypto.Sm4TypeHandler;
import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

/**
 * API 密钥
 */
@Table("sys_api_key")
public class SysApiKey extends BaseEntity {

	private String tenantId;
	private String name;
	private String accessKey;
	@Column(typeHandler = Sm4TypeHandler.class)
	private String secretKey;
	private String scope;
	private Integer status;
	private String remark;
	/** 到期时间，空表示不限制 */
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private LocalDateTime expireTime;
	/** 允许调用的路径，逗号分隔，空表示不限制 */
	private String apiPath;

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getAccessKey() {
		return accessKey;
	}

	public void setAccessKey(String accessKey) {
		this.accessKey = accessKey;
	}

	public String getSecretKey() {
		return secretKey;
	}

	public void setSecretKey(String secretKey) {
		this.secretKey = secretKey;
	}

	public String getScope() {
		return scope;
	}

	public void setScope(String scope) {
		this.scope = scope;
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

	public LocalDateTime getExpireTime() {
		return expireTime;
	}

	public void setExpireTime(LocalDateTime expireTime) {
		this.expireTime = expireTime;
	}

	public String getApiPath() {
		return apiPath;
	}

	public void setApiPath(String apiPath) {
		this.apiPath = apiPath;
	}
}
