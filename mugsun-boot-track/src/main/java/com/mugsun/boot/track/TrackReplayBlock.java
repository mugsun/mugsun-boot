package com.mugsun.boot.track;

/**
 * 回放块摄入载体：/track/replay 同步路径校验通过后的单块，经内存有界队列送达消费器落储。
 * <p>gzBytes 恒为 gzip 字节（gzip=true 块为客户端原样字节；gzip=false 明文块由摄入侧服务端补压），
 * 同步路径已验证内容（解压后 ≤1MB、rrweb 事件数组），消费侧不再解压/再压缩，原样写入对象存储——
 * 存储/读取/键名 .gz 单一口径，读侧无分支。
 * <p>rrwebEvents/durationMs/pageCount 由同步路径从解压后的 rrweb 事件数组解析（不信客户端 event_count 上报值）；
 * tenantId 服务端裁定（会话已存在取会话租户——与事件流 token 裁定同口径，否则 app_key 映射）。
 */
public class TrackReplayBlock {

	/** 接入应用标识（已过存在性 + enabled + replay_enabled 校验） */
	private String appKey;
	/** 会话 ID（已过路径安全字符集校验） */
	private String sessionId;
	/** 块序号（会话内自 0 递增） */
	private int seq;
	/** 客户端原样 gzip 字节（rrweb 事件数组 JSON 的 gzip） */
	private byte[] gzBytes;
	/** 解压后字节数（体积累计/展示口径） */
	private int decompressedBytes;
	/** rrweb 事件条数（服务端解析为准） */
	private int rrwebEvents;
	/** 块内容时长（毫秒）：块内 rrweb 事件 timestamp 极差（客户端相对时长，时钟漂移安全） */
	private long durationMs;
	/** 块内首个 rrweb 事件时间戳（epoch 毫秒；会话时长墙钟口径的 LEAST 来源） */
	private long firstEventTs;
	/** 块内末个 rrweb 事件时间戳（epoch 毫秒；会话时长墙钟口径的 GREATEST 来源） */
	private long lastEventTs;
	/** 块内全量快照次数（rrweb type=2；page_count 近似口径：页面加载/路由重建次数） */
	private int pageCount;
	/** 服务端接收时间（epoch 毫秒；首块决定 start_time 与对象键 yyyyMM 段） */
	private long receivedAtMs;
	/** 归属租户（服务端裁定，恒非空） */
	private String tenantId;
	/** 落储重试计数（消费器内部使用） */
	private int attempts;

	public String getAppKey() {
		return appKey;
	}

	public void setAppKey(String appKey) {
		this.appKey = appKey;
	}

	public String getSessionId() {
		return sessionId;
	}

	public void setSessionId(String sessionId) {
		this.sessionId = sessionId;
	}

	public int getSeq() {
		return seq;
	}

	public void setSeq(int seq) {
		this.seq = seq;
	}

	public byte[] getGzBytes() {
		return gzBytes;
	}

	public void setGzBytes(byte[] gzBytes) {
		this.gzBytes = gzBytes;
	}

	public int getDecompressedBytes() {
		return decompressedBytes;
	}

	public void setDecompressedBytes(int decompressedBytes) {
		this.decompressedBytes = decompressedBytes;
	}

	public int getRrwebEvents() {
		return rrwebEvents;
	}

	public void setRrwebEvents(int rrwebEvents) {
		this.rrwebEvents = rrwebEvents;
	}

	public long getDurationMs() {
		return durationMs;
	}

	public void setDurationMs(long durationMs) {
		this.durationMs = durationMs;
	}

	public long getFirstEventTs() {
		return firstEventTs;
	}

	public void setFirstEventTs(long firstEventTs) {
		this.firstEventTs = firstEventTs;
	}

	public long getLastEventTs() {
		return lastEventTs;
	}

	public void setLastEventTs(long lastEventTs) {
		this.lastEventTs = lastEventTs;
	}

	public int getPageCount() {
		return pageCount;
	}

	public void setPageCount(int pageCount) {
		this.pageCount = pageCount;
	}

	public long getReceivedAtMs() {
		return receivedAtMs;
	}

	public void setReceivedAtMs(long receivedAtMs) {
		this.receivedAtMs = receivedAtMs;
	}

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public int getAttempts() {
		return attempts;
	}

	public void setAttempts(int attempts) {
		this.attempts = attempts;
	}
}
