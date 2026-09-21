package com.mugsun.boot.track.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Web Vitals 分位直方图预聚合（track 库）：rollup 阶段维护固定分桶直方图，看板插值 p50/p75/p95
 * （实时 percentile_cont 需全排序不可行；桶计数天然可累加幂等，同窗口重算先清后写或按窗口覆盖）。
 * <p>本表按设计无 is_deleted 列（直方图按窗口覆盖重写，无逻辑删除语义），故不继承 BaseEntity，
 * 仅保留雪花主键 + 审计时间；桶定义按 metric 独立，入 TrackConstants。
 */
@Table("track_stats_vitals")
public class TrackStatsVitals implements Serializable {

	/** 雪花主键 */
	@Id(keyType = KeyType.Generator, value = KeyGenerators.flexId)
	private Long id;

	/** 接入应用标识 */
	private String appKey;
	/** 统计日（按 received_at 分日） */
	private LocalDate statDate;
	/** 指标：lcp/inp/cls/fcp/ttfb */
	private String metric;
	/** 可选按页维度（路由模板） */
	private String urlPath;
	/** 直方图桶序号（值域已知，对数桶） */
	private Integer bucket;
	/** 桶计数（增量累加） */
	private Long cnt;
	/** 归属租户 */
	private String tenantId;

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

	public String getMetric() {
		return metric;
	}

	public void setMetric(String metric) {
		this.metric = metric;
	}

	public String getUrlPath() {
		return urlPath;
	}

	public void setUrlPath(String urlPath) {
		this.urlPath = urlPath;
	}

	public Integer getBucket() {
		return bucket;
	}

	public void setBucket(Integer bucket) {
		this.bucket = bucket;
	}

	public Long getCnt() {
		return cnt;
	}

	public void setCnt(Long cnt) {
		this.cnt = cnt;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
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
