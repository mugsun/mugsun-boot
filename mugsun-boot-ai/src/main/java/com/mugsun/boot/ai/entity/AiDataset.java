package com.mugsun.boot.ai.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

@Table("ai_dataset")
public class AiDataset extends BaseEntity {

	private String tenantId;
	private String icon;
	private String name;
	private String description;
	private String datasetType;
	private Long modelId;
	private Integer enabled;
	private Integer maxRows;
	private Integer timeoutMs;

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getIcon() {
		return icon;
	}

	public void setIcon(String icon) {
		this.icon = icon;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getDatasetType() {
		return datasetType;
	}

	public void setDatasetType(String datasetType) {
		this.datasetType = datasetType;
	}

	public Long getModelId() {
		return modelId;
	}

	public void setModelId(Long modelId) {
		this.modelId = modelId;
	}

	public Integer getEnabled() {
		return enabled;
	}

	public void setEnabled(Integer enabled) {
		this.enabled = enabled;
	}

	public Integer getMaxRows() {
		return maxRows;
	}

	public void setMaxRows(Integer maxRows) {
		this.maxRows = maxRows;
	}

	public Integer getTimeoutMs() {
		return timeoutMs;
	}

	public void setTimeoutMs(Integer timeoutMs) {
		this.timeoutMs = timeoutMs;
	}

	/** 供重复提交按名称和启用状态归并，不展开描述原文。 */
	@Override
	public String toString() {
		String note = description == null ? "" : description;
		return (name == null ? "" : name) + "|"
			+ (enabled == null ? "" : enabled) + "|"
			+ note.length() + ":" + Integer.toHexString(note.hashCode()) + "|"
			+ (getId() == null ? "" : getId());
	}
}
