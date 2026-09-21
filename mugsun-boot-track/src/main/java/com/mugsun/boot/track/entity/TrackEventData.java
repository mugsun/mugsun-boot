package com.mugsun.boot.track.entity;

import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 长尾自定义属性 EAV（track 库，按 received_at 月分区，分区键跟随主表）。
 * <p>解决「不建 GIN 却要属性分布」的矛盾：属性分布看板不在主表 JSONB 上实时扫，而查本表
 * GROUP BY prop_key, str_value；按需启用防爆炸——仅 track_event_def 标记「可分析」的属性才拆入。
 * <p>分区表定制（不继承 BaseEntity）：复合主键 (id, received_at) 中仅 id 标注 @Id（flexId 雪花）；
 * 追加流无 is_deleted/审计时间，清理随主表 DROP 分区。
 */
@Table("track_event_data")
public class TrackEventData implements Serializable {

	/** 雪花主键（分区表复合主键含 received_at） */
	@Id(keyType = KeyType.Generator, value = KeyGenerators.flexId)
	private Long id;

	/** 关联事件 event_id */
	private String eventId;
	/** 接入应用标识 */
	private String appKey;
	/** 服务端接收时间（跟随主表分区键，TIMESTAMPTZ） */
	private LocalDateTime receivedAt;
	/** 属性键 */
	private String propKey;
	/** 字符串值 */
	private String strValue;
	/** 数值值 */
	private BigDecimal numValue;
	/** 归属租户（恒非空） */
	private String tenantId;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getEventId() {
		return eventId;
	}

	public void setEventId(String eventId) {
		this.eventId = eventId;
	}

	public String getAppKey() {
		return appKey;
	}

	public void setAppKey(String appKey) {
		this.appKey = appKey;
	}

	public LocalDateTime getReceivedAt() {
		return receivedAt;
	}

	public void setReceivedAt(LocalDateTime receivedAt) {
		this.receivedAt = receivedAt;
	}

	public String getPropKey() {
		return propKey;
	}

	public void setPropKey(String propKey) {
		this.propKey = propKey;
	}

	public String getStrValue() {
		return strValue;
	}

	public void setStrValue(String strValue) {
		this.strValue = strValue;
	}

	public BigDecimal getNumValue() {
		return numValue;
	}

	public void setNumValue(BigDecimal numValue) {
		this.numValue = numValue;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}
}
