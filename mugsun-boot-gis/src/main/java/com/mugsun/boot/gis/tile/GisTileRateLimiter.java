package com.mugsun.boot.gis.tile;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 瓦片代理限流：按用户、按 IP 两个维度的固定分钟窗计数（进程内）。
 * 任一维度超限即拒绝，被拒的请求不计入另一维度。多实例部署时阈值按单实例生效。
 */
public class GisTileRateLimiter {

	private final GisTileProperties props;
	private final LongSupplier clock;
	private final Map<String, Integer> counts = new HashMap<>();
	private long window = -1;

	public GisTileRateLimiter(GisTileProperties props) {
		this(props, System::currentTimeMillis);
	}

	public GisTileRateLimiter(GisTileProperties props, LongSupplier clock) {
		this.props = props;
		this.clock = clock;
	}

	/** @return true 放行；false 超限 */
	public synchronized boolean tryAcquire(String userId, String ip) {
		long now = clock.getAsLong() / 60_000L;
		if (now != window) {
			window = now;
			counts.clear();
		}
		String userKey = "u:" + (userId == null ? "" : userId);
		String ipKey = "ip:" + (ip == null ? "" : ip);
		int perUser = props.getRateLimitPerUser();
		int perIp = props.getRateLimitPerIp();
		int u = counts.getOrDefault(userKey, 0);
		int i = counts.getOrDefault(ipKey, 0);
		if ((perUser > 0 && u >= perUser) || (perIp > 0 && i >= perIp)) {
			return false;
		}
		counts.put(userKey, u + 1);
		counts.put(ipKey, i + 1);
		return true;
	}

	/** 当前窗口剩余秒数，用于 Retry-After */
	public long retryAfterSeconds() {
		long ms = clock.getAsLong();
		return Math.max(1, 60 - (ms / 1000L) % 60);
	}
}
