package com.mugsun.boot.ai.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

@Table("ai_channel_bind")
public class AiChannelBind extends BaseEntity {

	private String tenantId;
	private String channelCode;
	private String templateCode;
	private String alias;
	private String paramsSample;
	private Integer status;

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getChannelCode() {
		return channelCode;
	}

	public void setChannelCode(String channelCode) {
		this.channelCode = channelCode;
	}

	public String getTemplateCode() {
		return templateCode;
	}

	public void setTemplateCode(String templateCode) {
		this.templateCode = templateCode;
	}

	public String getAlias() {
		return alias;
	}

	public void setAlias(String alias) {
		this.alias = alias;
	}

	public String getParamsSample() {
		return paramsSample;
	}

	public void setParamsSample(String paramsSample) {
		this.paramsSample = paramsSample;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}

	/** 供重复提交按渠道、名称和状态归并，不展开 Webhook 原文。 */
	@Override
	public String toString() {
		String sample = paramsSample == null ? "" : paramsSample;
		String template = templateCode == null ? "" : templateCode;
		return (channelCode == null ? "" : channelCode) + "|"
			+ (alias == null ? "" : alias) + "|"
			+ (status == null ? "" : status) + "|"
			+ template.length() + ":" + Integer.toHexString(template.hashCode()) + "|"
			+ sample.length() + ":" + Integer.toHexString(sample.hashCode()) + "|"
			+ (getId() == null ? "" : getId());
	}
}
