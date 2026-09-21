package com.mugsun.boot.notify.entity;

import com.mugsun.boot.common.crypto.Sm4TypeHandler;
import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 通知渠道配置：库表驱动热更新；config JSON 禁类名（按渠道编码映射 Java 配置类），凭据存 secret 列（SM4 加密）。
 */
@Table("sys_notify_channel")
public class SysNotifyChannel extends BaseEntity {

	/** 渠道编码：in_app/mail/sms */
	private String channel;
	/** 渠道名称 */
	private String name;
	/** 状态：1 启用 / 0 停用 */
	private Integer status;
	/** 渠道非敏感配置 JSON */
	private String config;
	/** 渠道凭据（如 SMTP 密码）：SM4 密文落库、查询自动解密 */
	@Column(typeHandler = Sm4TypeHandler.class)
	private String secret;
	private String remark;

	public String getChannel() {
		return channel;
	}

	public void setChannel(String channel) {
		this.channel = channel;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}

	public String getConfig() {
		return config;
	}

	public void setConfig(String config) {
		this.config = config;
	}

	public String getSecret() {
		return secret;
	}

	public void setSecret(String secret) {
		this.secret = secret;
	}

	public String getRemark() {
		return remark;
	}

	public void setRemark(String remark) {
		this.remark = remark;
	}
}
