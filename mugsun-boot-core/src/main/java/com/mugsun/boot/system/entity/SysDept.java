package com.mugsun.boot.system.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mugsun.core.tool.tree.INode;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.util.List;

/**
 * 部门
 */
@Table("sys_dept")
public class SysDept extends BaseEntity implements INode<SysDept> {

	private Long parentId;
	private String deptName;
	private Integer sort;
	private String tenantId;

	@Column(ignore = true)
	private List<SysDept> children;

	@Override
	public Long getParentId() {
		return parentId;
	}

	public void setParentId(Long parentId) {
		this.parentId = parentId;
	}

	public String getDeptName() {
		return deptName;
	}

	public void setDeptName(String deptName) {
		this.deptName = deptName;
	}

	public Integer getSort() {
		return sort;
	}

	public void setSort(Integer sort) {
		this.sort = sort;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	@Override
	public List<SysDept> getChildren() {
		return children;
	}

	@Override
	public void setChildren(List<SysDept> children) {
		this.children = children;
	}
}
