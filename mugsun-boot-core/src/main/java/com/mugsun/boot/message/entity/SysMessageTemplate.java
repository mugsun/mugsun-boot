package com.mugsun.boot.message.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 站内信模板：title/content 含 ${key} 占位，发送时替换。
 */
@Table("sys_message_template")
public class SysMessageTemplate extends BaseEntity {

	/** 模板编码（唯一） */
	private String code;
	/** 标题模板（含 ${key}） */
	private String title;
	/** 内容模板（含 ${key}） */
	private String content;

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
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
}
