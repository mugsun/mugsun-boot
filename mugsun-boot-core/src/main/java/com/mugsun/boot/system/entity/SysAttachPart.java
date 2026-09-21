package com.mugsun.boot.system.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

/**
 * 附件分片登记：对齐 x-file-storage FilePartInfo，支撑分片上传会话的分片追踪与级联清理。
 */
@Table("sys_attach_part")
public class SysAttachPart extends BaseEntity {

	private String tenantId;
	private String platform;
	private String uploadId;
	private String eTag;
	private Integer partNumber;
	private Long partSize;
	/** 分片摘要 JSON（x-file-storage HashInfo 序列化，可空） */
	private String hashInfo;
	private LocalDateTime lastModified;

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getPlatform() {
		return platform;
	}

	public void setPlatform(String platform) {
		this.platform = platform;
	}

	public String getUploadId() {
		return uploadId;
	}

	public void setUploadId(String uploadId) {
		this.uploadId = uploadId;
	}

	public String getETag() {
		return eTag;
	}

	public void setETag(String eTag) {
		this.eTag = eTag;
	}

	public Integer getPartNumber() {
		return partNumber;
	}

	public void setPartNumber(Integer partNumber) {
		this.partNumber = partNumber;
	}

	public Long getPartSize() {
		return partSize;
	}

	public void setPartSize(Long partSize) {
		this.partSize = partSize;
	}

	public String getHashInfo() {
		return hashInfo;
	}

	public void setHashInfo(String hashInfo) {
		this.hashInfo = hashInfo;
	}

	public LocalDateTime getLastModified() {
		return lastModified;
	}

	public void setLastModified(LocalDateTime lastModified) {
		this.lastModified = lastModified;
	}
}
