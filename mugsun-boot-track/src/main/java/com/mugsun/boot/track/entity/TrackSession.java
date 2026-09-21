package com.mugsun.boot.track.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

/**
 * 会话物化表（track 库）：摄入消费按 session 增量 upsert 维护，会话指标全部查这张窄表，不扫事件流水。
 * <p>乱序安全语义：upsert 一律 LEAST/GREATEST/累加/置位，绝不用裸 {@code SET x = EXCLUDED.x}；
 * 批内同 session 先在消费侧聚合再 upsert（避免单条 ON CONFLICT 自冲突）。
 */
@Table("track_session")
public class TrackSession extends BaseEntity {

	/** 会话 ID */
	private String sessionId;
	/** 接入应用标识 */
	private String appKey;
	/** 归属租户 */
	private String tenantId;
	/** 匿名 ID（anonymous_id） */
	private String distinctId;
	/** 登录用户（服务端裁定） */
	private Long userId;
	/** 会话开始时间（upsert 取 LEAST） */
	private LocalDateTime startTime;
	/** 会话末事件时间（upsert 取 GREATEST） */
	private LocalDateTime endTime;
	/** 会话时长（毫秒，结算定稿） */
	private Integer durationMs;
	/** 页面浏览数（累加） */
	private Integer pageviews;
	/** 事件数（累加） */
	private Integer eventCount;
	/** 1=单 PV 跳出会话 */
	private Integer isBounce;
	/** 入口路径（仅更早事件到达时更新） */
	private String entryPath;
	/** 出口路径（仅更晚事件到达时更新） */
	private String exitPath;
	/** 来源域名 */
	private String referrerDomain;
	/** UTM 来源 */
	private String utmSource;
	/** 浏览器 */
	private String browser;
	/** 操作系统 */
	private String os;
	/** 设备类型：desktop/mobile/tablet */
	private String device;
	/** IP 归属地 */
	private String ipRegion;
	/** 1=会话内发生过 $error（回放筛选用） */
	private Integer hasError;
	/** 1=有回放数据（G100） */
	private Integer hasReplay;
	/** 1=会话已结算定稿（结算任务扫 idx_session_settle 部分索引） */
	private Integer settled;

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

	public LocalDateTime getEndTime() {
		return endTime;
	}

	public void setEndTime(LocalDateTime endTime) {
		this.endTime = endTime;
	}

	public Integer getDurationMs() {
		return durationMs;
	}

	public void setDurationMs(Integer durationMs) {
		this.durationMs = durationMs;
	}

	public Integer getPageviews() {
		return pageviews;
	}

	public void setPageviews(Integer pageviews) {
		this.pageviews = pageviews;
	}

	public Integer getEventCount() {
		return eventCount;
	}

	public void setEventCount(Integer eventCount) {
		this.eventCount = eventCount;
	}

	public Integer getIsBounce() {
		return isBounce;
	}

	public void setIsBounce(Integer isBounce) {
		this.isBounce = isBounce;
	}

	public String getEntryPath() {
		return entryPath;
	}

	public void setEntryPath(String entryPath) {
		this.entryPath = entryPath;
	}

	public String getExitPath() {
		return exitPath;
	}

	public void setExitPath(String exitPath) {
		this.exitPath = exitPath;
	}

	public String getReferrerDomain() {
		return referrerDomain;
	}

	public void setReferrerDomain(String referrerDomain) {
		this.referrerDomain = referrerDomain;
	}

	public String getUtmSource() {
		return utmSource;
	}

	public void setUtmSource(String utmSource) {
		this.utmSource = utmSource;
	}

	public String getBrowser() {
		return browser;
	}

	public void setBrowser(String browser) {
		this.browser = browser;
	}

	public String getOs() {
		return os;
	}

	public void setOs(String os) {
		this.os = os;
	}

	public String getDevice() {
		return device;
	}

	public void setDevice(String device) {
		this.device = device;
	}

	public String getIpRegion() {
		return ipRegion;
	}

	public void setIpRegion(String ipRegion) {
		this.ipRegion = ipRegion;
	}

	public Integer getHasError() {
		return hasError;
	}

	public void setHasError(Integer hasError) {
		this.hasError = hasError;
	}

	public Integer getHasReplay() {
		return hasReplay;
	}

	public void setHasReplay(Integer hasReplay) {
		this.hasReplay = hasReplay;
	}

	public Integer getSettled() {
		return settled;
	}

	public void setSettled(Integer settled) {
		this.settled = settled;
	}
}
