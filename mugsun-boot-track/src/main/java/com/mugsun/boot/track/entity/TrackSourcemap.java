package com.mugsun.boot.track.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * sourcemap 元数据（track 库，G101）：.map 本体存对象存储私有区，绝不进数据库。
 * <p>唯一键 (app_key, release, filename)：同应用同版本同名文件重传即覆盖（upsert）。
 * storage_* 三列记录写入时的平台坐标，读取/删除按原坐标重建 FileInfo——
 * 默认平台配置日后切换不影响存量文件寻址（与 track_replay 同纪律）。
 */
@Table("track_sourcemap")
public class TrackSourcemap extends BaseEntity {

	/** 接入应用标识 */
	private String appKey;
	/** 发布版本号（与 $error props.release 对齐，堆栈还原选图依据） */
	private String release;
	/** 原始 .map 文件名（同时作对象键文件名段） */
	private String filename;
	/** 对象存储完整对象键（含平台 basePath 前缀；管理端不下发） */
	private String storageKey;
	/** 写入时的 x-file-storage 平台名（读取/删除按原平台寻址） */
	private String storagePlatform;
	/** 写入时平台的 basePath（FileInfo 重建坐标；storage_key 含此前缀） */
	private String storageBasePath;
	/** 文件大小（字节） */
	private Long sizeBytes;
	/** 归属租户（服务端裁定，取上传操作人会话租户） */
	private String tenantId;
	/** 上传操作人用户 id */
	private Long createBy;

	public String getAppKey() {
		return appKey;
	}

	public void setAppKey(String appKey) {
		this.appKey = appKey;
	}

	public String getRelease() {
		return release;
	}

	public void setRelease(String release) {
		this.release = release;
	}

	public String getFilename() {
		return filename;
	}

	public void setFilename(String filename) {
		this.filename = filename;
	}

	public String getStorageKey() {
		return storageKey;
	}

	public void setStorageKey(String storageKey) {
		this.storageKey = storageKey;
	}

	public String getStoragePlatform() {
		return storagePlatform;
	}

	public void setStoragePlatform(String storagePlatform) {
		this.storagePlatform = storagePlatform;
	}

	public String getStorageBasePath() {
		return storageBasePath;
	}

	public void setStorageBasePath(String storageBasePath) {
		this.storageBasePath = storageBasePath;
	}

	public Long getSizeBytes() {
		return sizeBytes;
	}

	public void setSizeBytes(Long sizeBytes) {
		this.sizeBytes = sizeBytes;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public Long getCreateBy() {
		return createBy;
	}

	public void setCreateBy(Long createBy) {
		this.createBy = createBy;
	}
}
