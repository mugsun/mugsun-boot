package com.mugsun.boot.message.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 站内信消息主体：一条消息可发给多个收件人（见 SysMessageUser）。
 */
@Table("sys_message")
public class SysMessage extends BaseEntity {

	/** 标题（渲染后） */
	private String title;
	/** 内容（渲染后） */
	private String content;
	/** 类型：system/notice/todo */
	private String type;
	/** 发送人 id */
	private Long senderId;

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

	public Long getSenderId() {
		return senderId;
	}

	public void setSenderId(Long senderId) {
		this.senderId = senderId;
	}
}
