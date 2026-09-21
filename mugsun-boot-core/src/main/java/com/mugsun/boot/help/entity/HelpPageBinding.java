package com.mugsun.boot.help.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 帮助文档与前端路由的绑定：一页可绑多文档。
 */
@Table("help_page_binding")
public class HelpPageBinding extends BaseEntity {

	/** 前端路由路径，如 /system/user */
	private String routePath;
	/** 文档 id */
	private Long docId;
	/** 排序 */
	private Integer sort;

	public String getRoutePath() {
		return routePath;
	}

	public void setRoutePath(String routePath) {
		this.routePath = routePath;
	}

	public Long getDocId() {
		return docId;
	}

	public void setDocId(Long docId) {
		this.docId = docId;
	}

	public Integer getSort() {
		return sort;
	}

	public void setSort(Integer sort) {
		this.sort = sort;
	}
}
