package com.mugsun.boot.track.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

/**
 * 错误问题单（track 库）：按 app_key + fingerprint 聚合，支持指派 / 解决 / 复发。
 * <p>自然键唯一索引 uk_track_error_issue（is_deleted=0）；事件明细仍走 track_event，本表只存工作流状态与计数。
 */
@Table("track_error_issue")
public class TrackErrorIssue extends BaseEntity {

	private String appKey;
	private String fingerprint;
	/** 最近错误消息（截断） */
	private String title;
	/** OPEN / ASSIGNED / RESOLVED */
	private String status;
	private Long assigneeId;
	private String firstRelease;
	private String lastRelease;
	private LocalDateTime firstSeenAt;
	private LocalDateTime lastSeenAt;
	private LocalDateTime resolvedAt;
	private Long eventCount;
	private Long sessionCount;
	private Long userCount;
	/** 1=曾解决后同指纹再出现 */
	private Integer regressed;
	private String latestSessionId;
	private Long latestUserId;
	private String latestDistinctId;
	private String latestEventId;
	private String latestUrlPath;
	private String latestRoutePath;
	private String latestStack;
	/** 见过的 release 列表（JSON 数组文本，形如 ["1.0.0","1.0.1"]） */
	private String releases;
	private String tenantId;

	public String getAppKey() {
		return appKey;
	}

	public void setAppKey(String appKey) {
		this.appKey = appKey;
	}

	public String getFingerprint() {
		return fingerprint;
	}

	public void setFingerprint(String fingerprint) {
		this.fingerprint = fingerprint;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public Long getAssigneeId() {
		return assigneeId;
	}

	public void setAssigneeId(Long assigneeId) {
		this.assigneeId = assigneeId;
	}

	public String getFirstRelease() {
		return firstRelease;
	}

	public void setFirstRelease(String firstRelease) {
		this.firstRelease = firstRelease;
	}

	public String getLastRelease() {
		return lastRelease;
	}

	public void setLastRelease(String lastRelease) {
		this.lastRelease = lastRelease;
	}

	public LocalDateTime getFirstSeenAt() {
		return firstSeenAt;
	}

	public void setFirstSeenAt(LocalDateTime firstSeenAt) {
		this.firstSeenAt = firstSeenAt;
	}

	public LocalDateTime getLastSeenAt() {
		return lastSeenAt;
	}

	public void setLastSeenAt(LocalDateTime lastSeenAt) {
		this.lastSeenAt = lastSeenAt;
	}

	public LocalDateTime getResolvedAt() {
		return resolvedAt;
	}

	public void setResolvedAt(LocalDateTime resolvedAt) {
		this.resolvedAt = resolvedAt;
	}

	public Long getEventCount() {
		return eventCount;
	}

	public void setEventCount(Long eventCount) {
		this.eventCount = eventCount;
	}

	public Long getSessionCount() {
		return sessionCount;
	}

	public void setSessionCount(Long sessionCount) {
		this.sessionCount = sessionCount;
	}

	public Long getUserCount() {
		return userCount;
	}

	public void setUserCount(Long userCount) {
		this.userCount = userCount;
	}

	public Integer getRegressed() {
		return regressed;
	}

	public void setRegressed(Integer regressed) {
		this.regressed = regressed;
	}

	public String getLatestSessionId() {
		return latestSessionId;
	}

	public void setLatestSessionId(String latestSessionId) {
		this.latestSessionId = latestSessionId;
	}

	public Long getLatestUserId() {
		return latestUserId;
	}

	public void setLatestUserId(Long latestUserId) {
		this.latestUserId = latestUserId;
	}

	public String getLatestDistinctId() {
		return latestDistinctId;
	}

	public void setLatestDistinctId(String latestDistinctId) {
		this.latestDistinctId = latestDistinctId;
	}

	public String getLatestEventId() {
		return latestEventId;
	}

	public void setLatestEventId(String latestEventId) {
		this.latestEventId = latestEventId;
	}

	public String getLatestUrlPath() {
		return latestUrlPath;
	}

	public void setLatestUrlPath(String latestUrlPath) {
		this.latestUrlPath = latestUrlPath;
	}

	public String getLatestRoutePath() {
		return latestRoutePath;
	}

	public void setLatestRoutePath(String latestRoutePath) {
		this.latestRoutePath = latestRoutePath;
	}

	public String getLatestStack() {
		return latestStack;
	}

	public void setLatestStack(String latestStack) {
		this.latestStack = latestStack;
	}

	public String getReleases() {
		return releases;
	}

	public void setReleases(String releases) {
		this.releases = releases;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}
}
