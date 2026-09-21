package com.mugsun.boot.track.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

import java.time.LocalDateTime;

/**
 * 匿名 ID ↔ 登录用户映射（track 库）：UV/留存去重的归并依据（去重键 = coalesce(user_id, distinct_id)）。
 * <p>绑定语义：user_id 首绑写入后绝不覆盖，重复 identify 只刷 last_seen_time——共享设备先后登录两人时，
 * 匿名期历史归首绑用户，杜绝串号归并；一个 user 可绑多个 distinct_id（多设备/清缓存），user_id 侧非唯一。
 * <p>落库前提：identify 事件必须带有效登录 token 且 token.user_id == 上报的 user_id 才落本表，
 * 否则只记事件不建映射并计数告警（防伪造 identify 污染他人行为画像）。
 */
@Table("track_identity")
public class TrackIdentity extends BaseEntity {

	/** 接入应用标识 */
	private String appKey;
	/** 匿名 ID（anonymous_id） */
	private String distinctId;
	/** identify() 绑定的登录用户（首绑写入后绝不覆盖） */
	private Long userId;
	/** 归属租户（恒非空） */
	private String tenantId;
	/** 首次绑定时间 */
	private LocalDateTime firstBindTime;
	/** 最近出现时间（重复 identify 只刷本列） */
	private LocalDateTime lastSeenTime;

	public String getAppKey() {
		return appKey;
	}

	public void setAppKey(String appKey) {
		this.appKey = appKey;
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

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public LocalDateTime getFirstBindTime() {
		return firstBindTime;
	}

	public void setFirstBindTime(LocalDateTime firstBindTime) {
		this.firstBindTime = firstBindTime;
	}

	public LocalDateTime getLastSeenTime() {
		return lastSeenTime;
	}

	public void setLastSeenTime(LocalDateTime lastSeenTime) {
		this.lastSeenTime = lastSeenTime;
	}
}
