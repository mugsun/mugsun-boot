package com.mugsun.boot.track.entity;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.core.keygen.KeyGenerators;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 埋点事件流水（track 库，按 received_at 月分区）：亿级不可变追加流，热点属性成列，长尾 props jsonb。
 * <p>分区表定制（不继承 BaseEntity）：复合主键 (id, received_at) 中仅 id 标注 @Id（flexId 雪花）；
 * 按铁律豁免 is_deleted/update_time（清理走 DROP 分区而非逻辑删除），仅保留 create_time。
 * <p>三时间戳（均为 TIMESTAMPTZ）：client_ts 客户端原始时间（永不改写）＞ ts 校时修正后发生时间
 * （仅供展示/下钻，不参与聚合分窗）＞ received_at 服务端接收时间（分区键 + rollup 分窗基准，单调）。
 * <p>写入一律走摄入管道的批量原生 SQL（INSERT ... ON CONFLICT (event_id, received_at) DO NOTHING 兜底）；
 * 注意按主键 updateById 需同时携带分区键 received_at。
 */
@Table("track_event")
public class TrackEvent implements Serializable {

	/** 雪花主键（分区表复合主键含 received_at） */
	@Id(keyType = KeyType.Generator, value = KeyGenerators.flexId)
	private Long id;

	/** 客户端 UUID，跨重发幂等键（配合 Redis SETNX；不含任何被服务端改写的字段） */
	private String eventId;
	/** 接入应用标识 */
	private String appKey;
	/** 事件名（$pageview/$click 等内置或自定义） */
	private String eventName;
	/** 客户端原始时间（不改写；幂等判定 + 校时诊断） */
	private LocalDateTime clientTs;
	/** 分析用发生时间（校时修正后；仅供展示/下钻） */
	private LocalDateTime ts;
	/** 服务端接收时间（分区键 + rollup 分窗基准，单调） */
	private LocalDateTime receivedAt;
	/** 1=发生校时修正 */
	private Integer clockSkewed;
	/** 匿名 ID（anonymous_id） */
	private String distinctId;
	/** 服务端裁定的登录用户（非客户端直采；统计唯一事实源走 track_identity 归并） */
	private Long userId;
	/** 会话 ID */
	private String sessionId;
	/** 归属租户（恒非空：从 app_key 服务端映射，客户端传了也丢弃） */
	private String tenantId;
	/** 原始路径（明细展示用） */
	private String urlPath;
	/** 路由模板（如 /user/:id/detail），page 维度聚合用它防高基数 */
	private String routePath;
	/** 页面标题 */
	private String pageTitle;
	/** 来源域名 */
	private String referrerDomain;
	/** UTM 来源 */
	private String utmSource;
	/** UTM 媒介 */
	private String utmMedium;
	/** UTM 活动 */
	private String utmCampaign;
	/** 浏览器 */
	private String browser;
	/** 操作系统 */
	private String os;
	/** 设备类型：desktop/mobile/tablet */
	private String device;
	/** 客户端 IP（可配匿名化截断） */
	private String ip;
	/** IP 归属地 */
	private String ipRegion;
	/** 时长（$pageleave/计时事件） */
	private Integer durationMs;
	/** 错误指纹（仅 $error 有值：message+首帧 hash，错误分组聚合用） */
	private String errorFingerprint;
	/** 长尾自定义属性 JSON（截断：键≤64/值≤1024/总量≤16KB/深度≤3；jsonb 列，写入走原生 SQL ::jsonb） */
	private String props;

	/** 落库时间（插入时数据库 now() 填充） */
	@Column(onInsertValue = "now()")
	private LocalDateTime createTime;

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

	public String getEventName() {
		return eventName;
	}

	public void setEventName(String eventName) {
		this.eventName = eventName;
	}

	public LocalDateTime getClientTs() {
		return clientTs;
	}

	public void setClientTs(LocalDateTime clientTs) {
		this.clientTs = clientTs;
	}

	public LocalDateTime getTs() {
		return ts;
	}

	public void setTs(LocalDateTime ts) {
		this.ts = ts;
	}

	public LocalDateTime getReceivedAt() {
		return receivedAt;
	}

	public void setReceivedAt(LocalDateTime receivedAt) {
		this.receivedAt = receivedAt;
	}

	public Integer getClockSkewed() {
		return clockSkewed;
	}

	public void setClockSkewed(Integer clockSkewed) {
		this.clockSkewed = clockSkewed;
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

	public String getSessionId() {
		return sessionId;
	}

	public void setSessionId(String sessionId) {
		this.sessionId = sessionId;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getUrlPath() {
		return urlPath;
	}

	public void setUrlPath(String urlPath) {
		this.urlPath = urlPath;
	}

	public String getRoutePath() {
		return routePath;
	}

	public void setRoutePath(String routePath) {
		this.routePath = routePath;
	}

	public String getPageTitle() {
		return pageTitle;
	}

	public void setPageTitle(String pageTitle) {
		this.pageTitle = pageTitle;
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

	public String getUtmMedium() {
		return utmMedium;
	}

	public void setUtmMedium(String utmMedium) {
		this.utmMedium = utmMedium;
	}

	public String getUtmCampaign() {
		return utmCampaign;
	}

	public void setUtmCampaign(String utmCampaign) {
		this.utmCampaign = utmCampaign;
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

	public String getIp() {
		return ip;
	}

	public void setIp(String ip) {
		this.ip = ip;
	}

	public String getIpRegion() {
		return ipRegion;
	}

	public void setIpRegion(String ipRegion) {
		this.ipRegion = ipRegion;
	}

	public Integer getDurationMs() {
		return durationMs;
	}

	public void setDurationMs(Integer durationMs) {
		this.durationMs = durationMs;
	}

	public String getErrorFingerprint() {
		return errorFingerprint;
	}

	public void setErrorFingerprint(String errorFingerprint) {
		this.errorFingerprint = errorFingerprint;
	}

	public String getProps() {
		return props;
	}

	public void setProps(String props) {
		this.props = props;
	}

	public LocalDateTime getCreateTime() {
		return createTime;
	}

	public void setCreateTime(LocalDateTime createTime) {
		this.createTime = createTime;
	}
}
