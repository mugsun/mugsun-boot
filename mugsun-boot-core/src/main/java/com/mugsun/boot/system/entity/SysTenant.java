package com.mugsun.boot.system.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

/**
 * 租户
 */
@Table("sys_tenant")
public class SysTenant extends BaseEntity {

	private String tenantCode;
	private String tenantName;
	private String contactUser;
	private String contactPhone;
	private LocalDateTime expireTime;
	/** 所属套餐（NULL 表示不限功能） */
	private Long packageId;
	/** 状态：1正常 0停用 */
	private Integer status;
	/** 账号数上限（-1 不限制） */
	private Integer accountCount;

	public String getTenantCode() {
		return tenantCode;
	}

	public void setTenantCode(String tenantCode) {
		this.tenantCode = tenantCode;
	}

	public String getTenantName() {
		return tenantName;
	}

	public void setTenantName(String tenantName) {
		this.tenantName = tenantName;
	}

	public String getContactUser() {
		return contactUser;
	}

	public void setContactUser(String contactUser) {
		this.contactUser = contactUser;
	}

	public String getContactPhone() {
		return contactPhone;
	}

	public void setContactPhone(String contactPhone) {
		this.contactPhone = contactPhone;
	}

	public LocalDateTime getExpireTime() {
		return expireTime;
	}

	public void setExpireTime(LocalDateTime expireTime) {
		this.expireTime = expireTime;
	}

	public Long getPackageId() {
		return packageId;
	}

	public void setPackageId(Long packageId) {
		this.packageId = packageId;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}

	public Integer getAccountCount() {
		return accountCount;
	}

	public void setAccountCount(Integer accountCount) {
		this.accountCount = accountCount;
	}
}
