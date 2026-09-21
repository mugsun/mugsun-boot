package com.mugsun.boot.feedback.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 意见反馈：内容 + 可选联系方式 + 可选附件引用 + 提交人。
 */
@Table("sys_feedback")
public class SysFeedback extends BaseEntity {

	/** 反馈内容 */
	private String content;
	/** 联系方式（选填） */
	private String contact;
	/** 附件 id */
	private Long attachId;
	/** 附件文件名 */
	private String attachName;
	/** 附件地址 */
	private String attachUrl;
	/** 提交人 id */
	private Long userId;
	/** 处理状态：0未处理/1已处理 */
	private Integer status;

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public String getContact() {
		return contact;
	}

	public void setContact(String contact) {
		this.contact = contact;
	}

	public Long getAttachId() {
		return attachId;
	}

	public void setAttachId(Long attachId) {
		this.attachId = attachId;
	}

	public String getAttachName() {
		return attachName;
	}

	public void setAttachName(String attachName) {
		this.attachName = attachName;
	}

	public String getAttachUrl() {
		return attachUrl;
	}

	public void setAttachUrl(String attachUrl) {
		this.attachUrl = attachUrl;
	}

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}
}
