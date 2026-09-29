package com.mugsun.boot.ai.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

@Table("ai_knowledge_asset")
public class AiKnowledgeAsset extends BaseEntity {

	private Long knowledgeId;
	private String tenantId;
	private String fileName;
	private String fileUrl;
	private Long fileSize;
	private String fileType;
	private String segmentType;
	private Integer segmentLength;
	private Integer segmentOverlap;
	private String segmentSymbol;
	private Integer keepFormatFlag;
	private Integer extractMetaFlag;
	private String cleanRule;
	private Integer segmentCount;
	private String vectorStatus;
	private Integer progress;
	private String failReason;
	private String vectorStrategy;
	private String vectorPriority;
	private Integer batchSize;
	private Integer concurrency;

	/** 上传时可直接提交正文（无文件时用于分段/向量化） */
	private String contentText;

	public Long getKnowledgeId() {
		return knowledgeId;
	}

	public void setKnowledgeId(Long knowledgeId) {
		this.knowledgeId = knowledgeId;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getFileName() {
		return fileName;
	}

	public void setFileName(String fileName) {
		this.fileName = fileName;
	}

	public String getFileUrl() {
		return fileUrl;
	}

	public void setFileUrl(String fileUrl) {
		this.fileUrl = fileUrl;
	}

	public Long getFileSize() {
		return fileSize;
	}

	public void setFileSize(Long fileSize) {
		this.fileSize = fileSize;
	}

	public String getFileType() {
		return fileType;
	}

	public void setFileType(String fileType) {
		this.fileType = fileType;
	}

	public String getSegmentType() {
		return segmentType;
	}

	public void setSegmentType(String segmentType) {
		this.segmentType = segmentType;
	}

	public Integer getSegmentLength() {
		return segmentLength;
	}

	public void setSegmentLength(Integer segmentLength) {
		this.segmentLength = segmentLength;
	}

	public Integer getSegmentOverlap() {
		return segmentOverlap;
	}

	public void setSegmentOverlap(Integer segmentOverlap) {
		this.segmentOverlap = segmentOverlap;
	}

	public String getSegmentSymbol() {
		return segmentSymbol;
	}

	public void setSegmentSymbol(String segmentSymbol) {
		this.segmentSymbol = segmentSymbol;
	}

	public Integer getKeepFormatFlag() {
		return keepFormatFlag;
	}

	public void setKeepFormatFlag(Integer keepFormatFlag) {
		this.keepFormatFlag = keepFormatFlag;
	}

	public Integer getExtractMetaFlag() {
		return extractMetaFlag;
	}

	public void setExtractMetaFlag(Integer extractMetaFlag) {
		this.extractMetaFlag = extractMetaFlag;
	}

	public String getCleanRule() {
		return cleanRule;
	}

	public void setCleanRule(String cleanRule) {
		this.cleanRule = cleanRule;
	}

	public Integer getSegmentCount() {
		return segmentCount;
	}

	public void setSegmentCount(Integer segmentCount) {
		this.segmentCount = segmentCount;
	}

	public String getVectorStatus() {
		return vectorStatus;
	}

	public void setVectorStatus(String vectorStatus) {
		this.vectorStatus = vectorStatus;
	}

	public Integer getProgress() {
		return progress;
	}

	public void setProgress(Integer progress) {
		this.progress = progress;
	}

	public String getFailReason() {
		return failReason;
	}

	public void setFailReason(String failReason) {
		this.failReason = failReason;
	}

	public String getVectorStrategy() {
		return vectorStrategy;
	}

	public void setVectorStrategy(String vectorStrategy) {
		this.vectorStrategy = vectorStrategy;
	}

	public String getVectorPriority() {
		return vectorPriority;
	}

	public void setVectorPriority(String vectorPriority) {
		this.vectorPriority = vectorPriority;
	}

	public Integer getBatchSize() {
		return batchSize;
	}

	public void setBatchSize(Integer batchSize) {
		this.batchSize = batchSize;
	}

	public Integer getConcurrency() {
		return concurrency;
	}

	public void setConcurrency(Integer concurrency) {
		this.concurrency = concurrency;
	}

	public String getContentText() {
		return contentText;
	}

	public void setContentText(String contentText) {
		this.contentText = contentText;
	}
}
