package com.mugsun.boot.track.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

/**
 * 会话回放元数据（track 库，G100）：列表检索用；rrweb 本体存对象存储私有桶压缩块，绝不进数据库事实表。
 * <p>回放是最高敏感数据：短保留期（默认 14 天）到期删对象 + 逻辑删元数据；查看必写操作日志留痕。
 */
@Table("track_replay")
public class TrackReplay extends BaseEntity {

	/** 会话 ID */
	private String sessionId;
	/** 接入应用标识 */
	private String appKey;
	/** 归属租户 */
	private String tenantId;
	/** 匿名 ID */
	private String distinctId;
	/** 登录用户 */
	private Long userId;
	/** 会话开始时间 */
	private LocalDateTime startTime;
	/** 回放时长（毫秒） */
	private Integer durationMs;
	/** 页面数 */
	private Integer pageCount;
	/** rrweb 事件条数 */
	private Integer rrwebEvents;
	/** 压缩后体积（字节） */
	private Long sizeBytes;
	/** 1=会话内发生过 $error */
	private Integer hasError;
	/** 入口路径 */
	private String entryPath;
	/** 对象存储对象键（私有桶；首块键，多块会话的块清单按 seq 推导：dir(storage_key)+seq+".gz"） */
	private String storageKey;
	/** 已持久化的最大块序号（seq 自 0 连续递增；-1=尚无块） */
	private Integer lastSeq;
	/** 首块写入的 x-file-storage 平台名（读取/删除按原平台寻址） */
	private String storagePlatform;
	/** 首块写入时平台的 basePath（FileInfo 重建坐标；storage_key 含此前缀） */
	private String storageBasePath;
	/** 会话内首个 rrweb 事件时间戳（epoch 毫秒，upsert 取 LEAST；回放打点条定位锚） */
	private Long firstEventTs;
	/** 会话内末个 rrweb 事件时间戳（epoch 毫秒，upsert 取 GREATEST） */
	private Long lastEventTs;

	public String getSessionId() {
		return sessionId;
	}

	public void setSessionId(String sessionId) {
		this.sessionId = sessionId;
	}

	public String getAppKey() {
		return appKey;
	}

	public void setAppKey(String appKey) {
		this.appKey = appKey;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getDistinctId() {
		return distinctId;
	}

	public void setDistinctId(String distinctId) {
		this.distinctId = distinctId;
	}

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
	}

	public LocalDateTime getStartTime() {
		return startTime;
	}

	public void setStartTime(LocalDateTime startTime) {
		this.startTime = startTime;
	}

	public Integer getDurationMs() {
		return durationMs;
	}

	public void setDurationMs(Integer durationMs) {
		this.durationMs = durationMs;
	}

	public Integer getPageCount() {
		return pageCount;
	}

	public void setPageCount(Integer pageCount) {
		this.pageCount = pageCount;
	}

	public Integer getRrwebEvents() {
		return rrwebEvents;
	}

	public void setRrwebEvents(Integer rrwebEvents) {
		this.rrwebEvents = rrwebEvents;
	}

	public Long getSizeBytes() {
		return sizeBytes;
	}

	public void setSizeBytes(Long sizeBytes) {
		this.sizeBytes = sizeBytes;
	}

	public Integer getHasError() {
		return hasError;
	}

	public void setHasError(Integer hasError) {
		this.hasError = hasError;
	}

	public String getEntryPath() {
		return entryPath;
	}

	public void setEntryPath(String entryPath) {
		this.entryPath = entryPath;
	}

	public String getStorageKey() {
		return storageKey;
	}

	public void setStorageKey(String storageKey) {
		this.storageKey = storageKey;
	}

	public Integer getLastSeq() {
		return lastSeq;
	}

	public void setLastSeq(Integer lastSeq) {
		this.lastSeq = lastSeq;
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

	public Long getFirstEventTs() {
		return firstEventTs;
	}

	public void setFirstEventTs(Long firstEventTs) {
		this.firstEventTs = firstEventTs;
	}

	public Long getLastEventTs() {
		return lastEventTs;
	}

	public void setLastEventTs(Long lastEventTs) {
		this.lastEventTs = lastEventTs;
	}
}
