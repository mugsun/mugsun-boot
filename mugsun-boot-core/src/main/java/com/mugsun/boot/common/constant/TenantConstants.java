package com.mugsun.boot.common.constant;

/**
 * 租户常量。
 */
public interface TenantConstants {

	/** 默认（平台）租户编号：超管所属、自助注册默认落此租户 */
	String DEFAULT_TENANT_ID = "000000";

	/** 请求头携带的租户编号（防伪造一致性校验用；正常请求不需带，超管跨租户走会话 switch 机制） */
	String TENANT_HEADER = "X-Tenant-Id";
}
