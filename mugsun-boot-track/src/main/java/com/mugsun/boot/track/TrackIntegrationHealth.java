package com.mugsun.boot.track;

import com.mugsun.boot.common.constant.TrackConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接入自检记录（零 DB，纯 Redis，全部 fail-open：Redis 故障只丢自检数据，不影响摄入）：
 * <ul>
 *   <li>{@code health:recv:{appKey}} = 最近一次收下事件的时间（epoch ms），每批一次 SET；</li>
 *   <li>{@code health:rej:{appKey}:{yyyyMMdd}} hash：原因码 → 当日次数（UTC 日，TTL 8 天）；</li>
 *   <li>{@code health:last:{appKey}} hash：原因码 → "epochMs|样本说明"（只存服务端自己的提示文字，不存请求内容/用户数据）。</li>
 * </ul>
 * 只为<b>已存在</b>的应用记录（appKey 经本地缓存校验），随便填的 appKey 不会在 Redis 里造键。
 */
@Component
public class TrackIntegrationHealth {

	private static final Logger log = LoggerFactory.getLogger(TrackIntegrationHealth.class);

	static final String RECV_PREFIX = TrackConstants.REDIS_PREFIX + "health:recv:";
	static final String REJ_PREFIX = TrackConstants.REDIS_PREFIX + "health:rej:";
	static final String LAST_PREFIX = TrackConstants.REDIS_PREFIX + "health:last:";
	/** 计数保留天数（读取最近 7 天，多留 1 天跨时区余量） */
	static final int KEEP_DAYS = 8;
	static final int WINDOW_DAYS = 7;
	/** 超过这么久没收到事件即提示「可能断了」（前端据此标黄） */
	public static final long STALE_AFTER_MS = 24L * 3600 * 1000;
	private static final int SAMPLE_MAX_LEN = 200;
	private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

	private final StringRedisTemplate redis;
	private final TrackAppService appService;

	public TrackIntegrationHealth(StringRedisTemplate redis, TrackAppService appService) {
		this.redis = redis;
		this.appService = appService;
	}

	/** 收下事件（每批一次） */
	public void received(String appKey) {
		try {
			redis.opsForValue().set(RECV_PREFIX + appKey, String.valueOf(System.currentTimeMillis()),
				Duration.ofDays(400));
		} catch (RuntimeException e) {
			log.debug("自检记录失败（忽略）：{}", e.getMessage());
		}
	}

	/** 拒收（appKey 未经校验：只在应用存在时记录） */
	public void rejected(String appKey, TrackRejectReason reason, String sample) {
		if (reason == null || appKey == null || appKey.isBlank() || appKey.length() > TrackConstants.APP_KEY_MAX_LEN) {
			return;
		}
		try {
			if (appService.findByAppKey(appKey).isEmpty()) {
				return;
			}
		} catch (RuntimeException e) {
			return;
		}
		record(appKey, Map.of(reason, 1L), Map.of(reason, sample == null ? "" : sample));
	}

	/** 批量记录（appKey 已校验存在）：一次 pipeline 写完 */
	public void record(String appKey, Map<TrackRejectReason, Long> counts, Map<TrackRejectReason, String> samples) {
		if (counts.isEmpty()) {
			return;
		}
		String dayKey = REJ_PREFIX + appKey + ":" + LocalDate.now(ZoneOffset.UTC).format(DAY);
		String lastKey = LAST_PREFIX + appKey;
		String now = String.valueOf(System.currentTimeMillis());
		try {
			redis.executePipelined((RedisCallback<Object>) conn -> {
				byte[] day = dayKey.getBytes(StandardCharsets.UTF_8);
				byte[] last = lastKey.getBytes(StandardCharsets.UTF_8);
				for (Map.Entry<TrackRejectReason, Long> e : counts.entrySet()) {
					byte[] field = e.getKey().name().getBytes(StandardCharsets.UTF_8);
					conn.hashCommands().hIncrBy(day, field, e.getValue());
					String sample = samples.getOrDefault(e.getKey(), "");
					if (sample.length() > SAMPLE_MAX_LEN) {
						sample = sample.substring(0, SAMPLE_MAX_LEN);
					}
					conn.hashCommands().hSet(last, field, (now + "|" + sample).getBytes(StandardCharsets.UTF_8));
				}
				conn.keyCommands().expire(day, Duration.ofDays(KEEP_DAYS).toSeconds());
				conn.keyCommands().expire(last, Duration.ofDays(KEEP_DAYS).toSeconds());
				return null;
			});
		} catch (RuntimeException e) {
			log.debug("自检记录失败（忽略）：{}", e.getMessage());
		}
	}

	/** 最近收下事件时间；无记录/Redis 故障返回 null */
	public Long lastReceivedAt(String appKey) {
		try {
			String v = redis.opsForValue().get(RECV_PREFIX + appKey);
			return v == null ? null : Long.parseLong(v);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * 原因列表（只含最近 7 天出现过的），按 fail 优先、次数倒序：
	 * [{code, severity, description, suggestion, today, last7d, lastAt, lastMessage}]。
	 */
	public List<Map<String, Object>> reasons(String appKey) {
		Map<String, long[]> counts = new LinkedHashMap<>();
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		Map<Object, Object> last;
		try {
			for (int i = 0; i < WINDOW_DAYS; i++) {
				Map<Object, Object> day = redis.opsForHash().entries(REJ_PREFIX + appKey + ":" + today.minusDays(i).format(DAY));
				for (Map.Entry<Object, Object> e : day.entrySet()) {
					long[] c = counts.computeIfAbsent(e.getKey().toString(), k -> new long[2]);
					long n = Long.parseLong(e.getValue().toString());
					c[1] += n;
					if (i == 0) {
						c[0] += n;
					}
				}
			}
			last = redis.opsForHash().entries(LAST_PREFIX + appKey);
		} catch (RuntimeException e) {
			log.warn("读取接入自检数据失败：{}", e.getMessage());
			return List.of();
		}
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map.Entry<String, long[]> e : counts.entrySet()) {
			TrackRejectReason r;
			try {
				r = TrackRejectReason.valueOf(e.getKey());
			} catch (IllegalArgumentException ex) {
				continue;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("code", r.name());
			row.put("severity", r.severity());
			row.put("description", r.description());
			row.put("suggestion", r.suggestion());
			row.put("today", e.getValue()[0]);
			row.put("last7d", e.getValue()[1]);
			Object l = last.get(r.name());
			Long lastAt = null;
			String lastMessage = null;
			if (l != null) {
				String s = l.toString();
				int sep = s.indexOf('|');
				try {
					lastAt = Long.parseLong(sep < 0 ? s : s.substring(0, sep));
				} catch (NumberFormatException ignored) {
					// 坏数据只丢 lastAt
				}
				lastMessage = sep < 0 ? null : s.substring(sep + 1);
			}
			row.put("lastAt", lastAt);
			row.put("lastMessage", lastMessage == null || lastMessage.isEmpty() ? null : lastMessage);
			out.add(row);
		}
		out.sort((a, b) -> {
			int sa = "fail".equals(a.get("severity")) ? 0 : 1;
			int sb = "fail".equals(b.get("severity")) ? 0 : 1;
			if (sa != sb) {
				return sa - sb;
			}
			return Long.compare((Long) b.get("last7d"), (Long) a.get("last7d"));
		});
		return out;
	}
}
