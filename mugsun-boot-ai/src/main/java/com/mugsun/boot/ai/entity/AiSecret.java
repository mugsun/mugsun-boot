package com.mugsun.boot.ai.entity;

import com.mugsun.boot.common.crypto.Sm4TypeHandler;
import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.annotation.Column;
import java.time.LocalDateTime;

@Table("ai_secret")
public class AiSecret extends BaseEntity {

	private String tenantId;
	@Column(typeHandler = Sm4TypeHandler.class)
	private String secretKey;
	private String secretPrefix;
	private String scope;
	private Long scopeId;
	private String description;
	private LocalDateTime expireTime;
	private Integer rateLimit;
	private String allowIps;
	private LocalDateTime lastUsedTime;
	private Long usedCount;
	private Integer status;

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getSecretKey() {
		return secretKey;
	}

	public void setSecretKey(String secretKey) {
		this.secretKey = secretKey;
	}

	public String getSecretPrefix() {
		return secretPrefix;
	}

	public void setSecretPrefix(String secretPrefix) {
		this.secretPrefix = secretPrefix;
	}

	public String getScope() {
		return scope;
	}

	public void setScope(String scope) {
		this.scope = scope;
	}

	public Long getScopeId() {
		return scopeId;
	}

	public void setScopeId(Long scopeId) {
		this.scopeId = scopeId;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public LocalDateTime getExpireTime() {
		return expireTime;
	}

	public void setExpireTime(LocalDateTime expireTime) {
		this.expireTime = expireTime;
	}

	public Integer getRateLimit() {
		return rateLimit;
	}

	public void setRateLimit(Integer rateLimit) {
		this.rateLimit = rateLimit;
	}

	public String getAllowIps() {
		return allowIps;
	}

	public void setAllowIps(String allowIps) {
		this.allowIps = allowIps;
	}

	public LocalDateTime getLastUsedTime() {
		return lastUsedTime;
	}

	public void setLastUsedTime(LocalDateTime lastUsedTime) {
		this.lastUsedTime = lastUsedTime;
	}

	public Long getUsedCount() {
		return usedCount;
	}

	public void setUsedCount(Long usedCount) {
		this.usedCount = usedCount;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}
}
