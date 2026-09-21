package com.mugsun.boot.help.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 帮助文档：富文本内容，view_count 累计浏览量。
 */
@Table("help_doc")
public class HelpDoc extends BaseEntity {

	/** 所属目录 id */
	private Long catalogId;
	/** 文档标题 */
	private String title;
	/** 富文本内容 */
	private String content;
	/** 浏览量 */
	private Long viewCount;
	/** 排序 */
	private Integer sort;

	public Long getCatalogId() {
		return catalogId;
	}

	public void setCatalogId(Long catalogId) {
		this.catalogId = catalogId;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public Long getViewCount() {
		return viewCount;
	}

	public void setViewCount(Long viewCount) {
		this.viewCount = viewCount;
	}

	public Integer getSort() {
		return sort;
	}

	public void setSort(Integer sort) {
		this.sort = sort;
	}
}
