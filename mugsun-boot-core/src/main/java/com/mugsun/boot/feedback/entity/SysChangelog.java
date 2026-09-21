package com.mugsun.boot.feedback.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

/**
 * 版本更新记录：版本号 + 类型分类 + 富文本内容 + 发布时间。
 */
@Table("sys_changelog")
public class SysChangelog extends BaseEntity {

	/** 版本号，如 v1.2.0 */
	private String version;
	/** 类型：feature新增/optimize优化/fix修复 */
	private String type;
	/** 标题 */
	private String title;
	/** 富文本内容 */
	private String content;
	/** 发布时间 */
	private LocalDateTime publishTime;
	/** 排序 */
	private Integer sort;

	public String getVersion() {
		return version;
	}

	public void setVersion(String version) {
		this.version = version;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
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

	public LocalDateTime getPublishTime() {
		return publishTime;
	}

	public void setPublishTime(LocalDateTime publishTime) {
		this.publishTime = publishTime;
	}

	public Integer getSort() {
		return sort;
	}

	public void setSort(Integer sort) {
		this.sort = sort;
	}
}
