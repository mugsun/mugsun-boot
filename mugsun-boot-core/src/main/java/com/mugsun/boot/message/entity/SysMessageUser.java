package com.mugsun.boot.message.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

/**
 * 站内信收件人：每收件人一条读/未读状态；列表查询时装配所属消息内容（瞬态）。
 */
@Table("sys_message_user")
public class SysMessageUser extends BaseEntity {

	/** 消息 id */
	private Long messageId;
	/** 收件人 id */
	private Long userId;
	/** 是否已读：0未读/1已读 */
	private Integer isRead;
	/** 阅读时间 */
	private LocalDateTime readTime;

	/** 消息标题（关联装配，非库字段） */
	@Column(ignore = true)
	private String title;
	/** 消息内容（关联装配，非库字段） */
	@Column(ignore = true)
	private String content;
	/** 消息类型（关联装配，非库字段） */
	@Column(ignore = true)
	private String type;
	/** 消息发送时间（关联装配，非库字段） */
	@Column(ignore = true)
	private LocalDateTime sendTime;

	public Long getMessageId() {
		return messageId;
	}

	public void setMessageId(Long messageId) {
		this.messageId = messageId;
	}

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	public Integer getIsRead() {
		return isRead;
	}

	public void setIsRead(Integer isRead) {
		this.isRead = isRead;
	}

	public LocalDateTime getReadTime() {
		return readTime;
	}

	public void setReadTime(LocalDateTime readTime) {
		this.readTime = readTime;
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

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public LocalDateTime getSendTime() {
		return sendTime;
	}

	public void setSendTime(LocalDateTime sendTime) {
		this.sendTime = sendTime;
	}
}
