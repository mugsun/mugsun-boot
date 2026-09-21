package com.mugsun.boot.track.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * rollup 游标（track 库）：任务从游标后一窗口逐个补扫至当前窗口，每窗口重算覆盖后推进游标——
 * 任务宕机/跳窗不永久缺数（配合窗口幂等重算）。
 * <p>本表按设计无 is_deleted 列（游标只有推进语义），故不继承 BaseEntity，仅保留雪花主键 + 审计时间。
 */
@Table("track_rollup_cursor")
public class TrackRollupCursor implements Serializable {

	/** 雪花主键 */
	@Id(keyType = KeyType.Generator, value = KeyGenerators.flexId)
	private Long id;

	/** 任务键：stats_5m / stats_day / stats_vitals */
	private String jobKey;
	/** 接入应用标识 */
	private String appKey;
	/** 已聚合到的窗口（含） */
	private LocalDateTime lastBucket;

	/** 创建时间（插入时数据库 now() 填充） */
	@Column(onInsertValue = "now()")
	private LocalDateTime createTime;

	/** 更新时间（插入/更新时数据库 now() 填充） */
	@Column(onInsertValue = "now()", onUpdateValue = "now()")
	private LocalDateTime updateTime;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getJobKey() {
		return jobKey;
	}

	public void setJobKey(String jobKey) {
		this.jobKey = jobKey;
	}

	public String getAppKey() {
		return appKey;
	}

	public void setAppKey(String appKey) {
		this.appKey = appKey;
	}

	public LocalDateTime getLastBucket() {
		return lastBucket;
	}

	public void setLastBucket(LocalDateTime lastBucket) {
		this.lastBucket = lastBucket;
	}

	public LocalDateTime getCreateTime() {
		return createTime;
	}

	public void setCreateTime(LocalDateTime createTime) {
		this.createTime = createTime;
	}

	public LocalDateTime getUpdateTime() {
		return updateTime;
	}

	public void setUpdateTime(LocalDateTime updateTime) {
		this.updateTime = updateTime;
	}
}
