package com.mugsun.boot.system.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.util.List;

/**
 * 角色
 */
@Table("sys_role")
public class SysRole extends BaseEntity {

	private String roleName;
	private String roleCode;
	private Integer sort;
	private Integer dataScope;
	/** 自定义数据权限 SQL 片段（data_scope=6 生效） */
	private String customSql;
	private String tenantId;

	/** 自定义数据部门 id 集合（data_scope=5 时提交/回显，非持久化列，落 sys_role_dept） */
	@Column(ignore = true)
	private List<Long> deptIds;

	public String getRoleName() {
		return roleName;
	}

	public void setRoleName(String roleName) {
		this.roleName = roleName;
	}

	public String getRoleCode() {
		return roleCode;
	}

	public void setRoleCode(String roleCode) {
		this.roleCode = roleCode;
	}

	public Integer getSort() {
		return sort;
	}

	public void setSort(Integer sort) {
		this.sort = sort;
	}

	public Integer getDataScope() {
		return dataScope;
	}

	public void setDataScope(Integer dataScope) {
		this.dataScope = dataScope;
	}

	public String getCustomSql() {
		return customSql;
	}

	public void setCustomSql(String customSql) {
		this.customSql = customSql;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public List<Long> getDeptIds() {
		return deptIds;
	}

	public void setDeptIds(List<Long> deptIds) {
		this.deptIds = deptIds;
	}
}
