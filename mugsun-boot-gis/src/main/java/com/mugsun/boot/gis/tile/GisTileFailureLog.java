package com.mugsun.boot.gis.tile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 瓦片失败 WARN：按 host + 原因限频（默认每 60 秒一条，附带期间被抑制的次数）。
 * 只记 provider、host、原因、上游状态码和缩放级别，不记 URL、查询串或密钥。
 */
public class GisTileFailureLog {

	private static final Logger log = LoggerFactory.getLogger(GisTileFailureLog.class);
	private static final int MAX_KEYS = 1024;

	private final long windowMillis;
	private final LongSupplier clock;
	private final Map<String, long[]> slots = new ConcurrentHashMap<>();
	private final Sink sink;

	/** 输出端，单测可替换 */
	public interface Sink {
		void warn(String provider, String host, GisTileFailure reason, int upstreamStatus, int z, long suppressed);
	}

	public GisTileFailureLog() {
		this(60_000L, System::currentTimeMillis, GisTileFailureLog::defaultSink);
	}

	public GisTileFailureLog(long windowMillis, LongSupplier clock, Sink sink) {
		this.windowMillis = windowMillis;
		this.clock = clock;
		this.sink = sink;
	}

	/** host 只允许域名字符，防止日志注入；为空记为 "-" */
	static String safeHost(String host) {
		if (host == null || host.isBlank()) {
			return "-";
		}
		String h = host.toLowerCase(Locale.ROOT);
		return h.matches("[a-z0-9.\\-:\\[\\]]{1,253}") ? h : "invalid";
	}

	public void record(String provider, String host, GisTileFailure reason, int upstreamStatus, int z) {
		if (reason == null || !reason.warn()) {
			return;
		}
		String h = safeHost(host);
		String key = h + '|' + reason.code();
		long now = clock.getAsLong();
		if (slots.size() > MAX_KEYS) {
			slots.clear();
		}
		long[] slot = slots.computeIfAbsent(key, k -> new long[] { Long.MIN_VALUE, 0 });
		long suppressed;
		synchronized (slot) {
			if (slot[0] != Long.MIN_VALUE && now - slot[0] < windowMillis) {
				slot[1]++;
				return;
			}
			suppressed = slot[1];
			slot[0] = now;
			slot[1] = 0;
		}
		sink.warn(provider, h, reason, upstreamStatus, z, suppressed);
	}

	private static void defaultSink(String provider, String host, GisTileFailure reason, int upstreamStatus, int z,
									long suppressed) {
		log.warn("GIS tile proxy failed: provider={} host={} reason={} upstreamStatus={} z={} suppressedSinceLast={}",
			provider, host, reason.code(), upstreamStatus, z, suppressed);
	}
}
