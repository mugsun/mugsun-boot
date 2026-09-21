package com.mugsun.boot.track;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.constant.TenantConstants;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.system.service.ParamService;
import com.mugsun.boot.track.entity.TrackApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 回放摄入同步路径（/track/replay）：校验链复用摄入范式，同步路径零 DB 写。
 * <p><b>校验链顺序</b>（任一不过即拒，状态码见 {@link TrackCollectException}）：
 * 协议字段校验（app_key/session_id 路径安全字符集、seq 界、gzip 标记、payload 长度）
 * → appKey 存在且 enabled=1 且 replay_enabled=1（本地缓存）
 * → IP+appKey 分钟窗限流（独立键前缀，阈值 = collect × {@value TrackConstants#REPLAY_RATE_LIMIT_FACTOR}）
 * → 会话超限封禁检查（命中 413）
 * → base64 解码 + 还原明文（gzip=true 有界解压；gzip=false 明文同界兜底，解压后 ≤{@value TrackConstants#REPLAY_BLOCK_MAX_BYTES} 字节，超 413）
 * → rrweb 事件数组解析（条数/时长/全量快照数，不信 event_count 上报值）
 * → 块幂等（SETNX session+seq，重复块 200 duplicated 丢弃）
 * → 会话累计体积 INCRBY（解压后口径，超 sys_param 上限 → 置封禁键 + 413，该会话后续块一律 413 丢弃）
 * → 入内存有界队列（满 → 删幂等键 + 503，SDK 重发）。
 * <p><b>Redis 降级</b>：与事件摄入同策略——限流/幂等/计数 Redis 故障一律 fail-open 放行并告警，
 * 绝不让辅助通道反噬摄入主链路。
 * <p>刻意独立解析器（不复用 Spring 全局 ObjectMapper）：回放内容须原样留存，XSS 净化由播放侧渲染负责。
 */
@Service
public class TrackReplayService {

	private static final Logger log = LoggerFactory.getLogger(TrackReplayService.class);

	/** 独立于 Spring 全局实例的原始解析器（规避 XSS 净化反序列化器改写回放内容） */
	private static final ObjectMapper PLAIN_MAPPER = new ObjectMapper();
	/** 限流分钟窗格式化（键尾缀 yyyyMMddHHmm，与 collect 同窗口语义） */
	private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern(TrackConstants.RATE_LIMIT_MINUTE_PATTERN);
	/** gzip 解压分块大小 */
	private static final int GZIP_CHUNK_BYTES = 8192;

	private final TrackAppService appService;
	private final TrackReplayConsumer consumer;
	private final ParamService paramService;
	private final StringRedisTemplate redis;
	private final TrackIngestMetrics metrics;

	public TrackReplayService(TrackAppService appService, TrackReplayConsumer consumer,
							  ParamService paramService, StringRedisTemplate redis, TrackIngestMetrics metrics) {
		this.appService = appService;
		this.consumer = consumer;
		this.paramService = paramService;
		this.redis = redis;
		this.metrics = metrics;
	}

	/**
	 * 摄入一个回放块，返回 {accepted, duplicated}。
	 *
	 * @param root 请求 JSON（已解析、未净化原文）：{app_key, session_id, seq, event_count, gzip, payload:&lt;base64&gt;}
	 *             （gzip=true → payload=base64(gzip 块)；gzip=false → payload=base64(明文 JSON)，pagehide 收尾块协议）
	 * @param ip   上报端 IP（限流维度）
	 * @throws TrackCollectException 400 协议非法；413 单块/会话超限；429 限流；503 队列满（SDK 重发）
	 */
	public Map<String, Object> ingest(JsonNode root, String ip) {
		// 1) 协议字段：app_key/session_id 兼作对象键路径段，必须路径安全字符集（防路径穿越）
		String appKey = text(root, TrackConstants.FIELD_APP_KEY);
		if (appKey == null || appKey.length() > TrackConstants.APP_KEY_MAX_LEN
			|| !TrackConstants.REPLAY_PATH_SAFE.matcher(appKey).matches()) {
			throw new TrackCollectException(400, "无效 app_key");
		}
		String sessionId = text(root, TrackConstants.FIELD_SESSION_ID);
		if (sessionId == null || sessionId.length() > TrackConstants.SESSION_ID_MAX_LEN
			|| !TrackConstants.REPLAY_PATH_SAFE.matcher(sessionId).matches()) {
			throw new TrackCollectException(400, "无效 session_id");
		}
		JsonNode seqNode = root.get("seq");
		if (seqNode == null || !seqNode.isIntegralNumber()) {
			throw new TrackCollectException(400, "无效 seq");
		}
		long seq = seqNode.asLong();
		if (seq < 0 || seq > TrackConstants.REPLAY_SEQ_MAX) {
			throw new TrackCollectException(400, "seq 越界");
		}
		// gzip 缺省按 true（原协议只有压缩块）；SDK pagehide 收尾块显式 gzip=false = base64(明文 JSON)
		boolean gzipped = root.path("gzip").asBoolean(true);
		String payload = text(root, "payload");
		if (payload == null) {
			throw new TrackCollectException(400, "payload 缺失");
		}
		if (payload.length() > TrackConstants.REPLAY_PAYLOAD_B64_MAX_LEN) {
			throw new TrackCollectException(413, "回放块过大");
		}

		// 2) 应用校验：存在 + enabled=1 + replay_enabled=1（本地缓存 30s，生效延迟见 TrackAppService javadoc）
		TrackApp app = appService.findCollectable(appKey)
			.orElseThrow(() -> new TrackCollectException(400, "应用不存在或已停用"));
		if (app.getReplayEnabled() == null || app.getReplayEnabled() != 1) {
			throw new TrackCollectException(400, "应用未开启会话回放");
		}

		// 3) 限流（独立键前缀，阈值 = collect × 2；回放块大频次低）
		assertRateLimit(appKey, ip);

		// 4) 会话超限封禁检查（此前块已把会话累计顶破上限：后续块一律 413 丢弃）
		if (banned(sessionId)) {
			metrics.dropped("replay_banned", 1);
			throw new TrackCollectException(413, "该会话回放已超限截断");
		}

		// 5) base64 解码 → rrweb JSON 明文字节：gzip 块有界解压（防 zip 炸弹，解压后 ≤1MB）；
		//    明文块同界长度兜底；落储字节恒为 gzip（明文块服务端补压，存储/读取单一口径，键名 .gz 不自欺）
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(payload);
		} catch (IllegalArgumentException e) {
			throw new TrackCollectException(400, "payload base64 非法");
		}
		byte[] raw;
		byte[] storeBytes;
		if (gzipped) {
			raw = gunzip(decoded);
			storeBytes = decoded;
		} else {
			if (decoded.length > TrackConstants.REPLAY_BLOCK_MAX_BYTES) {
				throw new TrackCollectException(413, "回放块明文超限");
			}
			raw = decoded;
			storeBytes = gzip(raw);
		}

		// 6) rrweb 事件数组解析：条数/时长(timestamp 极差)/全量快照数(type=2) 以解析为准
		JsonNode events;
		try {
			events = PLAIN_MAPPER.readTree(raw);
		} catch (IOException e) {
			throw new TrackCollectException(400, "回放块内容非合法 JSON");
		}
		if (events == null || !events.isArray()) {
			throw new TrackCollectException(400, "回放块内容须为 rrweb 事件数组");
		}
		int eventCount = events.size();
		long minTs = Long.MAX_VALUE;
		long maxTs = Long.MIN_VALUE;
		int fullSnapshots = 0;
		for (JsonNode event : events) {
			JsonNode tsNode = event.path("timestamp");
			if (tsNode.isNumber()) {
				long ts = tsNode.asLong();
				minTs = Math.min(minTs, ts);
				maxTs = Math.max(maxTs, ts);
			}
			if (event.path("type").asInt(-1) == 2) {
				fullSnapshots++;
			}
		}
		long durationMs = eventCount == 0 || minTs == Long.MAX_VALUE ? 0 : Math.max(0, maxTs - minTs);

		// 7) 块幂等：同 session+seq 重复块丢弃（跨重发窗口 25h；先于体积累计，重复块不重复计体积）
		String seqKey = TrackConstants.REPLAY_SEQ_KEY_PREFIX + sessionId + ":" + seq;
		if (!markOnce(seqKey)) {
			metrics.replayDuplicated(1);
			return Map.of("accepted", true, "duplicated", true);
		}

		// 8) 会话累计体积（解压后口径）：超限 → 置封禁键 + 413；该会话后续块由步骤 4 一律丢弃
		if (!accountSize(sessionId, raw.length)) {
			metrics.dropped("replay_session_oversize", 1);
			throw new TrackCollectException(413, "单会话回放体积超限");
		}

		// 9) 入队（满 → 删幂等键放行重发 + 503；宁可丢块不拖垮存储/DB）
		TrackReplayBlock block = new TrackReplayBlock();
		block.setAppKey(appKey);
		block.setSessionId(sessionId);
		block.setSeq((int) seq);
		block.setGzBytes(storeBytes);
		block.setDecompressedBytes(raw.length);
		block.setRrwebEvents(eventCount);
		block.setDurationMs(durationMs);
		block.setPageCount(fullSnapshots);
		block.setReceivedAtMs(System.currentTimeMillis());
		// 首末事件时间戳：会话时长墙钟口径（duration_ms = 末 - 首）由 upsert 用 LEAST/GREATEST 归并
		block.setFirstEventTs(minTs == Long.MAX_VALUE ? block.getReceivedAtMs() : minTs);
		block.setLastEventTs(minTs == Long.MAX_VALUE ? block.getReceivedAtMs() : maxTs);
		block.setTenantId(app.getTenantId() != null && !app.getTenantId().isBlank()
			? app.getTenantId() : TenantConstants.DEFAULT_TENANT_ID);
		if (!consumer.offer(block)) {
			unmark(seqKey);
			metrics.dropped("replay_queue_full", 1);
			throw new TrackCollectException(503, "回放服务繁忙，请重试");
		}
		metrics.replayReceived(1);
		return Map.of("accepted", true, "duplicated", false);
	}

	/** IP+appKey 分钟窗限流（阈值 = collect 参数 × {@value TrackConstants#REPLAY_RATE_LIMIT_FACTOR}）：INCR 首置 EXPIRE；超限 429；Redis 故障 fail-open */
	private void assertRateLimit(String appKey, String ip) {
		int limit = paramInt(TrackConstants.PARAM_RATE_LIMIT, TrackConstants.DEFAULT_RATE_LIMIT)
			* TrackConstants.REPLAY_RATE_LIMIT_FACTOR;
		String key = TrackConstants.REPLAY_RATE_LIMIT_KEY_PREFIX + ip + ":" + appKey + ":" + MINUTE_FORMAT.format(LocalDateTime.now());
		try {
			Long count = redis.opsForValue().increment(key);
			if (count != null && count == 1L) {
				redis.expire(key, Duration.ofSeconds(TrackConstants.RATE_LIMIT_EXPIRE_SECONDS));
			}
			if (count != null && count > limit) {
				metrics.ratelimited();
				throw new TrackCollectException(429, "请求过于频繁");
			}
		} catch (TrackCollectException e) {
			throw e;
		} catch (RuntimeException e) {
			log.warn("回放限流计数 Redis 不可用，本次放行：{}", e.getMessage());
		}
	}

	/** 会话封禁检查；Redis 故障 fail-open（视为未封禁，体积累计同路径降级） */
	private boolean banned(String sessionId) {
		try {
			return Boolean.TRUE.equals(redis.hasKey(TrackConstants.REPLAY_BAN_KEY_PREFIX + sessionId));
		} catch (RuntimeException e) {
			log.warn("回放封禁检查 Redis 不可用，本次放行：{}", e.getMessage());
			return false;
		}
	}

	/** 块幂等：SETNX replay-seq:{session}:{seq} TTL 25h；Redis 故障 fail-open 放行（对象键覆盖写兜底，元数据可能多计一块，可接受） */
	private boolean markOnce(String seqKey) {
		try {
			Boolean first = redis.opsForValue().setIfAbsent(seqKey, "1", Duration.ofSeconds(TrackConstants.REPLAY_KEY_TTL_SECONDS));
			return !Boolean.FALSE.equals(first);
		} catch (RuntimeException e) {
			log.warn("回放幂等键 Redis 不可用，本次放行：{}", e.getMessage());
			return true;
		}
	}

	/** 撤销幂等键（入队失败放行 SDK 重发；尽力而为，失败则该块 25h 内重发被视为重复——概率与代价均可接受） */
	private void unmark(String seqKey) {
		try {
			redis.delete(seqKey);
		} catch (RuntimeException e) {
			log.warn("回放幂等键撤销失败（该块 25h 内重发将被幂等丢弃）：{}", e.getMessage());
		}
	}

	/**
	 * 会话累计体积记账：INCRBY replay-size:{session}（解压后字节）首置 EXPIRE 25h；
	 * 超 sys_param 上限（默认 {@value TrackConstants#DEFAULT_REPLAY_SESSION_MAX_BYTES}）置封禁键并返回 false。
	 * Redis 故障 fail-open 放行（上限保护失效但对象存储写入仍受单块 1MB 约束）。
	 */
	private boolean accountSize(String sessionId, int decompressedBytes) {
		long maxBytes = paramLong(TrackConstants.PARAM_REPLAY_SESSION_MAX, TrackConstants.DEFAULT_REPLAY_SESSION_MAX_BYTES);
		String sizeKey = TrackConstants.REPLAY_SIZE_KEY_PREFIX + sessionId;
		try {
			Long total = redis.opsForValue().increment(sizeKey, decompressedBytes);
			if (total != null && total == decompressedBytes) {
				redis.expire(sizeKey, Duration.ofSeconds(TrackConstants.REPLAY_KEY_TTL_SECONDS));
			}
			if (total != null && total > maxBytes) {
				redis.opsForValue().set(TrackConstants.REPLAY_BAN_KEY_PREFIX + sessionId, "1",
					Duration.ofSeconds(TrackConstants.REPLAY_KEY_TTL_SECONDS));
				return false;
			}
			return true;
		} catch (RuntimeException e) {
			log.warn("回放体积记账 Redis 不可用，本次放行：{}", e.getMessage());
			return true;
		}
	}

	/** 服务端补压：gzip=false 明文块统一转 gzip 落储（存储/读取恒 gzip 单一口径；单块 ≤1MB，开销可忽略） */
	private byte[] gzip(byte[] raw) {
		try (ByteArrayOutputStream out = new ByteArrayOutputStream();
			 GZIPOutputStream gzip = new GZIPOutputStream(out)) {
			gzip.write(raw);
			gzip.finish();
			return out.toByteArray();
		} catch (IOException e) {
			// ByteArrayOutputStream 实操不抛 IOException，防御兜底
			throw new TrackCollectException(500, "回放块压缩失败");
		}
	}

	/** 有界解压：解压后 ≤{@value TrackConstants#REPLAY_BLOCK_MAX_BYTES} 字节（超限 413；gzip 损坏 400） */
	private byte[] gunzip(byte[] raw) {
		try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(raw));
			 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			byte[] chunk = new byte[GZIP_CHUNK_BYTES];
			int total = 0;
			int n;
			while ((n = gzip.read(chunk)) != -1) {
				total += n;
				if (total > TrackConstants.REPLAY_BLOCK_MAX_BYTES) {
					throw new TrackCollectException(413, "回放块解压后超限");
				}
				out.write(chunk, 0, n);
			}
			return out.toByteArray();
		} catch (TrackCollectException e) {
			throw e;
		} catch (IOException e) {
			throw new TrackCollectException(400, "gzip 解压失败");
		}
	}

	/** sys_param 取整（缺失/非法/小于 1 回退兜底默认） */
	private int paramInt(String key, int defaultValue) {
		return (int) paramLong(key, defaultValue);
	}

	/** sys_param 取长整（缺失/非法/小于 1 回退兜底默认） */
	private long paramLong(String key, long defaultValue) {
		String value = paramService.getValue(key);
		if (value == null) {
			return defaultValue;
		}
		try {
			long parsed = Long.parseLong(value.trim());
			return parsed < 1 ? defaultValue : parsed;
		} catch (NumberFormatException e) {
			return defaultValue;
		}
	}

	/** 节点文本字段（非文本/空白归 null） */
	private String text(JsonNode node, String field) {
		if (node == null) {
			return null;
		}
		JsonNode value = node.get(field);
		if (value == null || value.isNull() || value.isContainerNode()) {
			return null;
		}
		String text = value.asText();
		return text == null || text.isBlank() ? null : text;
	}
}
