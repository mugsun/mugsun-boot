package com.mugsun.boot.system.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mugsun.core.tool.tree.INode;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.util.List;

/**
 * 系统字典（树：parent_id=0 为分类，子节点为字典项）
 */
@Table("sys_dict")
public class SysDict extends BaseEntity implements INode<SysDict> {

	private Long parentId;
	private String code;
	private String dictKey;
	private String dictValue;
	private Integer sort;
	private String remark;
	private Integer isSealed;
	private String color;

	@Column(ignore = true)
	private List<SysDict> children;

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
	public List<SysDict> getChildren() {
		return children;
	}

	@Override
	public void setChildren(List<SysDict> children) {
		this.children = children;
	}
}
