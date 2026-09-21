package com.mugsun.boot.track.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

/**
 * 5 分钟窗口聚合窄表（track 库）：只放可加和指标（去重类只进 day 表）。
 * <p>分窗基准 = received_at；写入幂等 = 窗口全量重算 + SET 覆盖（禁止 +EXCLUDED 累加，重跑/补扫双计）。
 * page 维度 dim_key 一律路由模板（防高基数撑爆唯一索引）。
 */
@Table("track_stats_5m")
public class TrackStats5m extends BaseEntity {

	/** 接入应用标识 */
	private String appKey;
	/** 5 分钟窗口起点（按 received_at 分窗） */
	private LocalDateTime bucketTime;
	/** 维度类型：event/page/referrer/device */
	private String dimType;
	/** 维度值：事件名/路由模板/域名/设备类型 */
	private String dimKey;
	/** 归属租户 */
	private String tenantId;
	/** 页面浏览数 */
	private Long pv;
	/** 事件数 */
	private Long eventCount;
	/** 窗口内活跃会话（去重类，采样时仅标注口径不外推） */
	private Long sessionCount;
	/** 时长合计（毫秒） */
	private Long durationSum;

	public String getAppKey() {
		return appKey;
	}

	public void setAppKey(String appKey) {
		this.appKey = appKey;
	}

	public LocalDateTime getBucketTime() {
		return bucketTime;
	}

	public void setBucketTime(LocalDateTime bucketTime) {
		this.bucketTime = bucketTime;
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

	public Long getDurationSum() {
		return durationSum;
	}

	public void setDurationSum(Long durationSum) {
		this.durationSum = durationSum;
	}
}
