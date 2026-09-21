package com.mugsun.boot.notify.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 统一通知模板：渠道无关，subject/content 含 ${key} 占位；required_params 保存期由渲染器抽取落库。
 */
@Table("sys_notify_template")
public class SysNotifyTemplate extends BaseEntity {

	/** 模板编码（唯一） */
	private String code;
	/** 模板名称 */
	private String name;
	/** 主题模板（站内信标题/邮件主题，含 ${key}） */
	private String subject;
	/** 内容模板（含 ${key}） */
	private String content;
	/** 必传占位参数（保存期抽取，逗号分隔） */
	private String requiredParams;
	/** 默认投递渠道（逗号分隔渠道编码） */
	private String channels;
	/** 状态：1 启用 / 0 停用 */
	private Integer status;
	private String remark;

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getSubject() {
		return subject;
	}

	public void setSubject(String subject) {
		this.subject = subject;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public String getRequiredParams() {
		return requiredParams;
	}

	public void setRequiredParams(String requiredParams) {
		this.requiredParams = requiredParams;
	}

	public String getChannels() {
		return channels;
	}

	public void setChannels(String channels) {
		this.channels = channels;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}

	public String getRemark() {
		return remark;
	}

	public void setRemark(String remark) {
		this.remark = remark;
	}
}
