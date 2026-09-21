package com.mugsun.boot.track;

/**
 * 摄入管道事件载体：collect 同步路径校验/裁定完成后的单条事件，经内存有界队列送达消费器落库。
 * <p>时间一律 epoch 毫秒三态：clientTsMs 客户端原始时间（永不改写）、tsMs 校时修正后发生时间、
 * receivedAtMs 服务端接收时间（分区键 + rollup 分窗基准）；落库时由 {@link TrackEventStore} 换算绑定。
 * <p>browser/os/device/ipRegion 由消费器富化（UA 解析 + IP 属地）；identifyUserId 非空表示该 $identify
 * 事件已通过 token 一致性核对、待消费侧绑定 track_identity；attempts 为落库重试计数（消费器内部使用）。
 */
public class TrackIngestEvent {

	/** 客户端 UUID，跨重发幂等键 */
	private String eventId;
	/** 接入应用标识（服务端校验通过） */
	private String appKey;
	/** 事件名（白名单内置或合法自定义名） */
	private String eventName;
	/** 客户端原始时间（epoch 毫秒，不改写） */
	private long clientTsMs;
	/** 校时修正后发生时间（epoch 毫秒） */
	private long tsMs;
	/** 服务端接收时间（epoch 毫秒，分区键） */
	private long receivedAtMs;
	/** 1=发生校时修正 */
	private int clockSkewed;
	/** 匿名 ID */
	private String distinctId;
	/** 会话 ID */
	private String sessionId;
	/** 归属租户（服务端裁定：token 租户优先，否则 app_key 映射；恒非空） */
	private String tenantId;
	/** 服务端裁定的登录用户（无 token 为 null，客户端上报值一律忽略） */
	private Long userId;
	/** 原始路径（props 热点提升） */
	private String urlPath;
	/** 路由模板（props 热点提升） */
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
	/** 时长（$pageleave/计时事件，毫秒） */
	private Integer durationMs;
	/** 错误指纹（仅 $error） */
	private String errorFingerprint;
	/** 截断净化后的 props JSON 串（jsonb 列） */
	private String propsJson;
	/** 上报端 IP（限流维度 + 消费侧属地解析） */
	private String ip;
	/** 上报端 User-Agent（消费侧解析 browser/os/device） */
	private String userAgent;
	/** SDK 平台（web/android/ios；UA 解析仅对 web 执行） */
	private String platform;
	/** $identify 待绑定的登录用户（已通过 token 一致性核对；null=不建映射） */
	private Long identifyUserId;
	/** 浏览器（消费侧富化） */
	private String browser;
	/** 操作系统（消费侧富化） */
	private String os;
	/** 设备类型 desktop/mobile/tablet（props 自报优先，UA 解析兜底） */
	private String device;
	/** IP 归属地（消费侧富化，可空） */
	private String ipRegion;
	/** WGS84 经度（摄入侧校验圆整，非法为 null） */
	private Double geoLon;
	/** WGS84 纬度（摄入侧校验圆整，非法为 null） */
	private Double geoLat;
	/** 落库重试计数（批次失败重回队列时递增） */
	private int attempts;

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

	public long getClientTsMs() {
		return clientTsMs;
	}

	public void setClientTsMs(long clientTsMs) {
		this.clientTsMs = clientTsMs;
	}

	public long getTsMs() {
		return tsMs;
	}

	public void setTsMs(long tsMs) {
		this.tsMs = tsMs;
	}

	public long getReceivedAtMs() {
		return receivedAtMs;
	}

	public void setReceivedAtMs(long receivedAtMs) {
		this.receivedAtMs = receivedAtMs;
	}

	public int getClockSkewed() {
		return clockSkewed;
	}

	public void setClockSkewed(int clockSkewed) {
		this.clockSkewed = clockSkewed;
	}

	public String getDistinctId() {
		return distinctId;
	}

	public void setDistinctId(String distinctId) {
		this.distinctId = distinctId;
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

	public Long getUserId() {
		return userId;
	}

	public void setUserId(Long userId) {
		this.userId = userId;
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

	public String getPropsJson() {
		return propsJson;
	}

	public void setPropsJson(String propsJson) {
		this.propsJson = propsJson;
	}

	public String getIp() {
		return ip;
	}

	public void setIp(String ip) {
		this.ip = ip;
	}

	public String getUserAgent() {
		return userAgent;
	}

	public void setUserAgent(String userAgent) {
		this.userAgent = userAgent;
	}

	public String getPlatform() {
		return platform;
	}

	public void setPlatform(String platform) {
		this.platform = platform;
	}

	public Long getIdentifyUserId() {
		return identifyUserId;
	}

	public void setIdentifyUserId(Long identifyUserId) {
		this.identifyUserId = identifyUserId;
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

	public Double getGeoLon() {
		return geoLon;
	}

	public void setGeoLon(Double geoLon) {
		this.geoLon = geoLon;
	}

	public Double getGeoLat() {
		return geoLat;
	}

	public void setGeoLat(Double geoLat) {
		this.geoLat = geoLat;
	}

	public int getAttempts() {
		return attempts;
	}

	public void setAttempts(int attempts) {
		this.attempts = attempts;
	}
}
