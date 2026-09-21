package com.mugsun.boot.track.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

import java.time.LocalDate;

/**
 * 天级聚合窄表（track 库）：UV/跳出等去重类指标只进本表，凌晨从昨日分区精确算（经 track_identity 归并）。
 * <p>UV 不可从子窗口相加；分日基准 = received_at（迟到数据归入接收当日，历史窗口不被追改）。
 */
@Table("track_stats_day")
public class TrackStatsDay extends BaseEntity {

	/** 接入应用标识 */
	private String appKey;
	/** 统计日（按 received_at 分日） */
	private LocalDate statDate;
	/** 维度类型：overview/event/page/referrer/device */
	private String dimType;
	/** 维度值（page 维度用路由模板） */
	private String dimKey;
	/** 归属租户 */
	private String tenantId;
	/** 页面浏览数 */
	private Long pv;
	/** 独立访客（精确去重：count(distinct coalesce(user_id, distinct_id))） */
	private Long uv;
	/** 会话数 */
	private Long sessionCount;
	/** 跳出会话数 */
	private Long bounceCount;
	/** 时长合计（毫秒） */
	private Long durationSum;
	/** 事件数 */
	private Long eventCount;

	public String getAppKey() {
		return appKey;
	}

	public void setAppKey(String appKey) {
		this.appKey = appKey;
	}

	public LocalDate getStatDate() {
		return statDate;
	}

	public void setStatDate(LocalDate statDate) {
		this.statDate = statDate;
	}

	public String getDimType() {
		return dimType;
	}

	public void setDimType(String dimType) {
		this.dimType = dimType;
	}

	public String getDimKey() {
		return dimKey;
	}

	public void setDimKey(String dimKey) {
		this.dimKey = dimKey;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public Long getPv() {
		return pv;
	}

	public void setPv(Long pv) {
		this.pv = pv;
	}

	public Long getUv() {
		return uv;
	}

	public void setUv(Long uv) {
		this.uv = uv;
	}

	public Long getSessionCount() {
		return sessionCount;
	}

	public void setSessionCount(Long sessionCount) {
		this.sessionCount = sessionCount;
	}

	public Long getBounceCount() {
		return bounceCount;
	}

	public void setBounceCount(Long bounceCount) {
		this.bounceCount = bounceCount;
	}

	public Long getDurationSum() {
		return durationSum;
	}

	public void setDurationSum(Long durationSum) {
		this.durationSum = durationSum;
	}

	public Long getEventCount() {
		return eventCount;
	}

	public void setEventCount(Long eventCount) {
		this.eventCount = eventCount;
	}
}
