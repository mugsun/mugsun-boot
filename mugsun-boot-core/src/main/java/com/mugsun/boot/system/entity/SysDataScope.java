package com.mugsun.boot.system.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 数据权限规则。启用后登记受控表；范围比角色更严时，该表按规则收紧。
 */
@Table("sys_data_scope")
public class SysDataScope extends BaseEntity {

	private String resourceCode;
	private String scopeName;
	private Long menuId;
	private String tableName;
	private String deptColumn;
	private String userColumn;
	/** 1 全部 / 2 本部门 / 3 本部门及子部门 / 4 仅本人 */
	private Integer scopeType;
	/** 1 启用 / 0 停用 */
	private Integer status;
	private String remark;

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

	public Long getMenuId() {
		return menuId;
	}

	public void setMenuId(Long menuId) {
		this.menuId = menuId;
	}

	public String getTableName() {
		return tableName;
	}

	public void setTableName(String tableName) {
		this.tableName = tableName;
	}

	public String getDeptColumn() {
		return deptColumn;
	}

	public void setDeptColumn(String deptColumn) {
		this.deptColumn = deptColumn;
	}

	public String getUserColumn() {
		return userColumn;
	}

	public void setUserColumn(String userColumn) {
		this.userColumn = userColumn;
	}

	public Integer getScopeType() {
		return scopeType;
	}

	public void setScopeType(Integer scopeType) {
		this.scopeType = scopeType;
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
