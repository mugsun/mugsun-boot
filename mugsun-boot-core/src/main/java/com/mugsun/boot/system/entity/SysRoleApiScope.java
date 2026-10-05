package com.mugsun.boot.system.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 角色与接口权限的授权关系。
 */
@Table("sys_role_api_scope")
public class SysRoleApiScope extends BaseEntity {

	private Long roleId;
	private Long apiScopeId;

	public Long getRoleId() {
		return roleId;
	}

	public void setRoleId(Long roleId) {
		this.roleId = roleId;
	}

	public Long getApiScopeId() {
		return apiScopeId;
	}

	public void setApiScopeId(Long apiScopeId) {
		this.apiScopeId = apiScopeId;
	}
}
