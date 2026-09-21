package com.mugsun.boot.system.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mugsun.core.tool.tree.INode;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.util.List;

/**
 * 业务字典（结构同系统字典，附租户隔离字段）
 */
@Table("sys_dict_biz")
public class SysDictBiz extends BaseEntity implements INode<SysDictBiz> {

	private String tenantId;
	private Long parentId;
	private String code;
	private String dictKey;
	private String dictValue;
	private Integer sort;
	private String remark;
	private Integer isSealed;
	private String color;

	@Column(ignore = true)
	private List<SysDictBiz> children;

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	@Override
	public Long getParentId() {
		return parentId;
	}

	public void setParentId(Long parentId) {
		this.parentId = parentId;
	}

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public String getDictKey() {
		return dictKey;
	}

	public void setDictKey(String dictKey) {
		this.dictKey = dictKey;
	}

	public String getDictValue() {
		return dictValue;
	}

	public void setDictValue(String dictValue) {
		this.dictValue = dictValue;
	}

	public Integer getSort() {
		return sort;
	}

	public void setSort(Integer sort) {
		this.sort = sort;
	}

	public String getRemark() {
		return remark;
	}

	public void setRemark(String remark) {
		this.remark = remark;
	}

	public Integer getIsSealed() {
		return isSealed;
	}

	public void setIsSealed(Integer isSealed) {
		this.isSealed = isSealed;
	}

	public String getColor() {
		return color;
	}

	public void setColor(String color) {
		this.color = color;
	}

	@Override
	public List<SysDictBiz> getChildren() {
		return children;
	}

	@Override
	public void setChildren(List<SysDictBiz> children) {
		this.children = children;
	}
}
