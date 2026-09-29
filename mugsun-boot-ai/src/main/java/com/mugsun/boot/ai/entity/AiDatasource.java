package com.mugsun.boot.ai.entity;

import com.mugsun.boot.common.crypto.Sm4TypeHandler;
import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.annotation.Column;

@Table("ai_datasource")
public class AiDatasource extends BaseEntity {

	private String tenantId;
	private String name;
	private String dbType;
	private String driverClass;
	private String jdbcUrl;
	private String username;
	@Column(typeHandler = Sm4TypeHandler.class)
	private String password;
	private Integer readOnlyFlag;
	private String tableWhitelist;
	private Integer poolInitialSize;
	private Integer poolMaxActive;
	private Integer poolMaxWait;
	private String poolValidationQuery;
	private String remark;

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

	public String getDbType() {
		return dbType;
	}

	public void setDbType(String dbType) {
		this.dbType = dbType;
	}

	public String getDriverClass() {
		return driverClass;
	}

	public void setDriverClass(String driverClass) {
		this.driverClass = driverClass;
	}

	public String getJdbcUrl() {
		return jdbcUrl;
	}

	public void setJdbcUrl(String jdbcUrl) {
		this.jdbcUrl = jdbcUrl;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public Integer getReadOnlyFlag() {
		return readOnlyFlag;
	}

	public void setReadOnlyFlag(Integer readOnlyFlag) {
		this.readOnlyFlag = readOnlyFlag;
	}

	public String getTableWhitelist() {
		return tableWhitelist;
	}

	public void setTableWhitelist(String tableWhitelist) {
		this.tableWhitelist = tableWhitelist;
	}

	public Integer getPoolInitialSize() {
		return poolInitialSize;
	}

	public void setPoolInitialSize(Integer poolInitialSize) {
		this.poolInitialSize = poolInitialSize;
	}

	public Integer getPoolMaxActive() {
		return poolMaxActive;
	}

	public void setPoolMaxActive(Integer poolMaxActive) {
		this.poolMaxActive = poolMaxActive;
	}

	public Integer getPoolMaxWait() {
		return poolMaxWait;
	}

	public void setPoolMaxWait(Integer poolMaxWait) {
		this.poolMaxWait = poolMaxWait;
	}

	public String getPoolValidationQuery() {
		return poolValidationQuery;
	}

	public void setPoolValidationQuery(String poolValidationQuery) {
		this.poolValidationQuery = poolValidationQuery;
	}

	public String getRemark() {
		return remark;
	}

	public void setRemark(String remark) {
		this.remark = remark;
	}
}
