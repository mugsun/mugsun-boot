package com.mugsun.boot.system.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 接口权限规则。启用后，除平台管理员外只有被授权的角色能调用该路径。
 */
@Table("sys_api_scope")
public class SysApiScope extends BaseEntity {

	private String resourceCode;
	private String scopeName;
	private String scopePath;
	/** 1 系统接口 / 2 业务接口 */
	private Integer scopeType;
	private Long menuId;
	/** 1 启用 / 0 停用 */
	private Integer status;
	private String remark;
	/** 当前租户已授权的角色数，仅回显 */
	@Column(ignore = true)
	private Integer grantCount;

	public String getResourceCode() {
		return resourceCode;
	}

	public void setResourceCode(String resourceCode) {
		this.resourceCode = resourceCode;
	}

	public String getScopeName() {
		return scopeName;
	}

	public void setScopeName(String scopeName) {
		this.scopeName = scopeName;
	}

	public String getScopePath() {
		return scopePath;
	}

	public void setScopePath(String scopePath) {
		this.scopePath = scopePath;
	}

	public Integer getScopeType() {
		return scopeType;
	}

	public void setScopeType(Integer scopeType) {
		this.scopeType = scopeType;
	}

	public Long getMenuId() {
		return menuId;
	}

	public void setMenuId(Long menuId) {
		this.menuId = menuId;
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

	public Integer getGrantCount() {
		return grantCount;
	}

	public void setGrantCount(Integer grantCount) {
		this.grantCount = grantCount;
	}
}
