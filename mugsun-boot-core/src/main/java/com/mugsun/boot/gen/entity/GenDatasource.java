package com.mugsun.boot.gen.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

/**
 * 代码生成用的外部数据源。口令只落库，接口不回传。
 */
@Table("sys_gen_datasource")
public class GenDatasource extends BaseEntity {

	private String tenantId;
	private String dsName;
	private String jdbcUrl;
	private String username;
	private String password;
	private String driverClass;
	private String remark;
	/** 1 已连上 / 0 未连上 */
	private Integer connectStatus;
	private String connectMsg;
	private LocalDateTime connectTime;

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getDsName() {
		return dsName;
	}

	public void setDsName(String dsName) {
		this.dsName = dsName;
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

	public String getDriverClass() {
		return driverClass;
	}

	public void setDriverClass(String driverClass) {
		this.driverClass = driverClass;
	}

	public String getRemark() {
		return remark;
	}

	public void setRemark(String remark) {
		this.remark = remark;
	}

	public Integer getConnectStatus() {
		return connectStatus;
	}

	public void setConnectStatus(Integer connectStatus) {
		this.connectStatus = connectStatus;
	}

	public String getConnectMsg() {
		return connectMsg;
	}

	public void setConnectMsg(String connectMsg) {
		this.connectMsg = connectMsg;
	}

	public LocalDateTime getConnectTime() {
		return connectTime;
	}

	public void setConnectTime(LocalDateTime connectTime) {
		this.connectTime = connectTime;
	}
}
