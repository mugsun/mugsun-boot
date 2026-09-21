package com.mugsun.boot.system.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知公告
 */
@Table("sys_notice")
public class SysNotice extends BaseEntity {

	private String tenantId;
	private String title;
	private String content;
	private String category;
	private Integer isTop;
	private LocalDateTime releaseTime;
	/** 可见范围：1全部可见/0按范围 */
	private Integer allVisible;
	/** 浏览用户数（去重） */
	private Integer viewUv;
	/** 浏览次数（累计） */
	private Integer viewPv;

	/** 可见范围明细（提交/回显用，非库字段） */
	@Column(ignore = true)
	private List<NoticeScopeItem> scopeList;
	/** 当前用户是否已读（我的通知列表装配，非库字段） */
	@Column(ignore = true)
	private Boolean readFlag;

	/** 可见范围项：scopeType 1员工/2部门 + scopeId */
	public record NoticeScopeItem(Integer scopeType, Long scopeId) {
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
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

	public String getCategory() {
		return category;
	}

	public void setCategory(String category) {
		this.category = category;
	}

	public Integer getIsTop() {
		return isTop;
	}

	public void setIsTop(Integer isTop) {
		this.isTop = isTop;
	}

	public LocalDateTime getReleaseTime() {
		return releaseTime;
	}

	public void setReleaseTime(LocalDateTime releaseTime) {
		this.releaseTime = releaseTime;
	}

	public Integer getAllVisible() {
		return allVisible;
	}

	public void setAllVisible(Integer allVisible) {
		this.allVisible = allVisible;
	}

	public Integer getViewUv() {
		return viewUv;
	}

	public void setViewUv(Integer viewUv) {
		this.viewUv = viewUv;
	}

	public Integer getViewPv() {
		return viewPv;
	}

	public void setViewPv(Integer viewPv) {
		this.viewPv = viewPv;
	}

	public List<NoticeScopeItem> getScopeList() {
		return scopeList;
	}

	public void setScopeList(List<NoticeScopeItem> scopeList) {
		this.scopeList = scopeList;
	}

	public Boolean getReadFlag() {
		return readFlag;
	}

	public void setReadFlag(Boolean readFlag) {
		this.readFlag = readFlag;
	}
}
