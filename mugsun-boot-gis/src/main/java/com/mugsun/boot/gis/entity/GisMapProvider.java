package com.mugsun.boot.gis.entity;

import com.mugsun.boot.common.crypto.Sm4TypeHandler;
import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;

/**
 * 租户底图供应商配置：api_key SM4 密文；管理端不回传明文。
 */
@Table("gis_map_provider")
public class GisMapProvider extends BaseEntity {

	private String tenantId;
	private String provider;
	private Integer enabled;
	@Column(typeHandler = Sm4TypeHandler.class)
	private String apiKey;
	@Column(typeHandler = Sm4TypeHandler.class)
	private String secret;
	private String extraJson;
	private String remark;

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getProvider() {
		return provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	public Integer getEnabled() {
		return enabled;
	}

	public void setEnabled(Integer enabled) {
		this.enabled = enabled;
	}

	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	public String getSecret() {
		return secret;
	}

	public void setSecret(String secret) {
		this.secret = secret;
	}

	public String getExtraJson() {
		return extraJson;
	}

	public void setExtraJson(String extraJson) {
		this.extraJson = extraJson;
	}

	public String getRemark() {
		return remark;
	}

	public void setRemark(String remark) {
		this.remark = remark;
	}
}
