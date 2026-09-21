package com.mugsun.boot.message;

import java.util.List;
import java.util.Map;

/**
 * 发送站内信入参：可选模板 + 占位变量 + 收件人。
 */
public class MessageSendDTO {

	/** 模板 id（可选，指定则用模板 title/content） */
	private Long templateId;
	/** 标题（未用模板时） */
	private String title;
	/** 内容（未用模板时） */
	private String content;
	/** 类型 */
	private String type;
	/** 占位变量 */
	private Map<String, String> params;
	/** 收件人 id 列表 */
	private List<Long> recipientIds;

	public Long getTemplateId() {
		return templateId;
	}

	public void setTemplateId(Long templateId) {
		this.templateId = templateId;
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

	public Map<String, String> getParams() {
		return params;
	}

	public void setParams(Map<String, String> params) {
		this.params = params;
	}

	public List<Long> getRecipientIds() {
		return recipientIds;
	}

	public void setRecipientIds(List<Long> recipientIds) {
		this.recipientIds = recipientIds;
	}
}
