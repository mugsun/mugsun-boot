package com.mugsun.boot.datasource.entity;

import com.mugsun.boot.common.crypto.Sm4TypeHandler;
import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 租户独立数据源配置（平台级，无 tenant_id 列不参与租户隔离）。
 * 配置后运行时注册进 FlexDataSource，该租户业务数据按 DataSourceKey 路由到独立库/独立 schema。
 */
@Table("sys_tenant_datasource")
public class SysTenantDatasource extends BaseEntity {

	private String tenantCode;
	private String dsUrl;
	private String dsUsername;
	/** 独立库密码：SM4 密文落库、查询自动解密（存量明文由 Sm4Util.decrypt 容错读取） */
	@Column(typeHandler = Sm4TypeHandler.class)
	private String dsPassword;
	/** 隔离策略：1独立库 2独立schema */
	private Integer isolationType;
	/** schema 隔离模式的目标 schema */
	private String schemaName;
	private Integer status;
	private String remark;

	public String getTenantCode() {
		return tenantCode;
	}

	public void setTenantCode(String tenantCode) {
		this.tenantCode = tenantCode;
	}

	public String getDsUrl() {
		return dsUrl;
	}

	public void setDsUrl(String dsUrl) {
		this.dsUrl = dsUrl;
	}

	public String getDsUsername() {
		return dsUsername;
	}

	public void setDsUsername(String dsUsername) {
		this.dsUsername = dsUsername;
	}

	public String getDsPassword() {
		return dsPassword;
	}

	public void setDsPassword(String dsPassword) {
		this.dsPassword = dsPassword;
	}

	public Integer getIsolationType() {
		return isolationType;
	}

	public void setIsolationType(Integer isolationType) {
		this.isolationType = isolationType;
	}

	public String getSchemaName() {
		return schemaName;
	}

	public void setSchemaName(String schemaName) {
		this.schemaName = schemaName;
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
