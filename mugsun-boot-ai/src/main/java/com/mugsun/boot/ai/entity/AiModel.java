package com.mugsun.boot.ai.entity;

import com.mugsun.boot.common.crypto.Sm4TypeHandler;
import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.annotation.Column;
import java.math.BigDecimal;

@Table("ai_model")
public class AiModel extends BaseEntity {

	private String tenantId;
	private String modelName;
	private String modelType;
	private String provider;
	private String icon;
	private String baseUrl;
	private String modelCode;
	@Column(typeHandler = Sm4TypeHandler.class)
	private String apiKey;
	@Column(typeHandler = Sm4TypeHandler.class)
	private String secretKey;
	private Integer dimensions;
	private BigDecimal priceInput;
	private BigDecimal priceOutput;
	private String paramsJson;
	private Integer visionFlag;
	private Integer activateFlag;
	private Integer defaultFlag;
	private Integer builtinFlag;
	private String remark;

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getModelName() {
		return modelName;
	}

	public void setModelName(String modelName) {
		this.modelName = modelName;
	}

	public String getModelType() {
		return modelType;
	}

	public void setModelType(String modelType) {
		this.modelType = modelType;
	}

	public String getProvider() {
		return provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	public String getIcon() {
		return icon;
	}

	public void setIcon(String icon) {
		this.icon = icon;
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public void setBaseUrl(String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public String getModelCode() {
		return modelCode;
	}

	public void setModelCode(String modelCode) {
		this.modelCode = modelCode;
	}

	public String getApiKey() {
		return apiKey;
	}

	public void setApiKey(String apiKey) {
		this.apiKey = apiKey;
	}

	public String getSecretKey() {
		return secretKey;
	}

	public void setSecretKey(String secretKey) {
		this.secretKey = secretKey;
	}

	public Integer getDimensions() {
		return dimensions;
	}

	public void setDimensions(Integer dimensions) {
		this.dimensions = dimensions;
	}

	public BigDecimal getPriceInput() {
		return priceInput;
	}

	public void setPriceInput(BigDecimal priceInput) {
		this.priceInput = priceInput;
	}

	public BigDecimal getPriceOutput() {
		return priceOutput;
	}

	public void setPriceOutput(BigDecimal priceOutput) {
		this.priceOutput = priceOutput;
	}

	public String getParamsJson() {
		return paramsJson;
	}

	public void setParamsJson(String paramsJson) {
		this.paramsJson = paramsJson;
	}

	public Integer getVisionFlag() {
		return visionFlag;
	}

	public void setVisionFlag(Integer visionFlag) {
		this.visionFlag = visionFlag;
	}

	public Integer getActivateFlag() {
		return activateFlag;
	}

	public void setActivateFlag(Integer activateFlag) {
		this.activateFlag = activateFlag;
	}

	public Integer getDefaultFlag() {
		return defaultFlag;
	}

	public void setDefaultFlag(Integer defaultFlag) {
		this.defaultFlag = defaultFlag;
	}

	public Integer getBuiltinFlag() {
		return builtinFlag;
	}

	public void setBuiltinFlag(Integer builtinFlag) {
		this.builtinFlag = builtinFlag;
	}

	public String getRemark() {
		return remark;
	}

	public void setRemark(String remark) {
		this.remark = remark;
	}
}
