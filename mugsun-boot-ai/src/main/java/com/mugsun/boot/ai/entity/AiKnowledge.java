package com.mugsun.boot.ai.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Table;
import java.math.BigDecimal;

@Table("ai_knowledge")
public class AiKnowledge extends BaseEntity {

	private String tenantId;
	private String icon;
	private String name;
	private String description;
	private Long vectorStoreId;
	private Long embeddingModelId;
	private Integer dimensions;
	private String retrievalMode;
	private Integer topK;
	private BigDecimal minScore;
	private Integer rerankFlag;
	private Long rerankModelId;
	private Integer status;

	/** 列表展示：资料数（非表字段） */
	@Column(ignore = true)
	private Integer docCount;
	/** 列表展示：分段数（非表字段） */
	@Column(ignore = true)
	private Integer segmentCount;

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

	public Long getVectorStoreId() {
		return vectorStoreId;
	}

	public void setVectorStoreId(Long vectorStoreId) {
		this.vectorStoreId = vectorStoreId;
	}

	public Long getEmbeddingModelId() {
		return embeddingModelId;
	}

	public void setEmbeddingModelId(Long embeddingModelId) {
		this.embeddingModelId = embeddingModelId;
	}

	public Integer getDimensions() {
		return dimensions;
	}

	public void setDimensions(Integer dimensions) {
		this.dimensions = dimensions;
	}

	public String getRetrievalMode() {
		return retrievalMode;
	}

	public void setRetrievalMode(String retrievalMode) {
		this.retrievalMode = retrievalMode;
	}

	public Integer getTopK() {
		return topK;
	}

	public void setTopK(Integer topK) {
		this.topK = topK;
	}

	public BigDecimal getMinScore() {
		return minScore;
	}

	public void setMinScore(BigDecimal minScore) {
		this.minScore = minScore;
	}

	public Integer getRerankFlag() {
		return rerankFlag;
	}

	public void setRerankFlag(Integer rerankFlag) {
		this.rerankFlag = rerankFlag;
	}

	public Long getRerankModelId() {
		return rerankModelId;
	}

	public void setRerankModelId(Long rerankModelId) {
		this.rerankModelId = rerankModelId;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}

	public Integer getDocCount() {
		return docCount;
	}

	public void setDocCount(Integer docCount) {
		this.docCount = docCount;
	}

	public Integer getSegmentCount() {
		return segmentCount;
	}

	public void setSegmentCount(Integer segmentCount) {
		this.segmentCount = segmentCount;
	}
}
