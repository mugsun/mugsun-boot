package com.mugsun.boot.help.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mugsun.core.tool.tree.INode;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.util.List;

/**
 * 帮助文档目录：树形，parent_id=0 为根。
 */
@Table("help_catalog")
public class HelpCatalog extends BaseEntity implements INode<HelpCatalog> {

	/** 父目录 id，0 为根 */
	private Long parentId;
	/** 目录名称 */
	private String name;
	/** 排序 */
	private Integer sort;
	/** 子目录（树形装配，非库字段） */
	@Column(ignore = true)
	private List<HelpCatalog> children;

	public Long getParentId() {
		return parentId;
	}

	public void setParentId(Long parentId) {
		this.parentId = parentId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Integer getSort() {
		return sort;
	}

	public void setSort(Integer sort) {
		this.sort = sort;
	}

	public List<HelpCatalog> getChildren() {
		return children;
	}

	public void setChildren(List<HelpCatalog> children) {
		this.children = children;
	}
}
