package com.mugsun.boot.ai.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

@Table("ai_knowledge_segment")
public class AiKnowledgeSegment extends BaseEntity {

	private Long knowledgeId;
	private Long assetId;
	private String tenantId;
	private Integer seq;
	private String content;
	private String auxContent;
	private String metaJson;
	private Integer tokenCount;
	private String embeddingId;
	private String vectorStatus;
	private Integer enabled;

	public Long getKnowledgeId() {
		return knowledgeId;
	}

	public void setKnowledgeId(Long knowledgeId) {
		this.knowledgeId = knowledgeId;
	}

	public Long getAssetId() {
		return assetId;
	}

	public void setAssetId(Long assetId) {
		this.assetId = assetId;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public Integer getSeq() {
		return seq;
	}

	public void setSeq(Integer seq) {
		this.seq = seq;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public String getAuxContent() {
		return auxContent;
	}

	public void setAuxContent(String auxContent) {
		this.auxContent = auxContent;
	}

	public String getMetaJson() {
		return metaJson;
	}

	public void setMetaJson(String metaJson) {
		this.metaJson = metaJson;
	}

	public Integer getTokenCount() {
		return tokenCount;
	}

	public void setTokenCount(Integer tokenCount) {
		this.tokenCount = tokenCount;
	}

	public String getEmbeddingId() {
		return embeddingId;
	}

	public void setEmbeddingId(String embeddingId) {
		this.embeddingId = embeddingId;
	}

	public String getVectorStatus() {
		return vectorStatus;
	}

	public void setVectorStatus(String vectorStatus) {
		this.vectorStatus = vectorStatus;
	}

	public Integer getEnabled() {
		return enabled;
	}

	public void setEnabled(Integer enabled) {
		this.enabled = enabled;
	}
}
