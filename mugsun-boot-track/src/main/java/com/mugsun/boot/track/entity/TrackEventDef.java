package com.mugsun.boot.track.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

/**
 * 事件元数据治理（track 库）：collect 遇到未见事件名自动注册（first/last_seen），管理端认领补充显示名/说明；
 * 事件名白名单与属性「可分析」标记（track_event_data 拆表依据）的载体。
 */
@Table("track_event_def")
public class TrackEventDef extends BaseEntity {

	/** 接入应用标识 */
	private String appKey;
	/** 事件名 */
	private String eventName;
	/** 显示名（管理端认领补充） */
	private String displayName;
	/** 事件说明 */
	private String description;
	/** 状态：1 启用 / 0 停用（停用=采集端拒收） */
	private Integer status;
	/** 负责人 */
	private String owner;
	/** 首次采集时间（自动注册） */
	private LocalDateTime firstSeenTime;
	/** 最近采集时间 */
	private LocalDateTime lastSeenTime;
	/** 归属租户 */
	private String tenantId;

	public String getAppKey() {
		return appKey;
	}

	public void setAppKey(String appKey) {
		this.appKey = appKey;
	}

	public String getEventName() {
		return eventName;
	}

	public void setEventName(String eventName) {
		this.eventName = eventName;
	}

	public String getDisplayName() {
		return displayName;
	}

	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public Integer getStatus() {
		return status;
	}

	public void setStatus(Integer status) {
		this.status = status;
	}

	public String getOwner() {
		return owner;
	}

	public void setOwner(String owner) {
		this.owner = owner;
	}

	public LocalDateTime getFirstSeenTime() {
		return firstSeenTime;
	}

	public void setFirstSeenTime(LocalDateTime firstSeenTime) {
		this.firstSeenTime = firstSeenTime;
	}

	public LocalDateTime getLastSeenTime() {
		return lastSeenTime;
	}

	public void setLastSeenTime(LocalDateTime lastSeenTime) {
		this.lastSeenTime = lastSeenTime;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}
}
