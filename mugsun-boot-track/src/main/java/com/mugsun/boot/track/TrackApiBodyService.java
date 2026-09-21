package com.mugsun.boot.track;

import com.fasterxml.jackson.databind.JsonNode;
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
 * 接口响应体摄入同步路径（/track/api-body，G102）：校验链复用回放范式，同步路径零 DB 写、同步落对象存储。
 * <p>响应体永不进事件队列/事件表（GB 级内存风险，§19 推演雷 1）：api_request 事件 props 仅带
 * {@value TrackConstants#PROP_BODY_REF}（= 事件自身 event_id），body 经本独立通道落对象存储。
 * <p><b>校验链顺序</b>（任一不过即拒，状态码见 {@link TrackCollectException}）：
 * 协议字段校验（app_key/event_id/payload 存在与长度、b64 预检）
 * → appKey 存在且 enabled=1 且 api_body_enabled=1（本地缓存）
 * → IP+appKey 分钟窗限流（独立键前缀，阈值 = collect 同级）
 * → event_id 路径安全字符集（兼作对象键文件名段，防路径穿越）
 * → base64 解码 + 还原明文（gzip=true 有界解压；gzip=false 明文同界兜底，解压后 ≤ sys_param 上限，超 413）
 * → 幂等（SETNX api-body:{event_id} TTL 25h，重复上传 200 duplicated 丢弃）
 * → 落对象存储（明文体服务端补压 gzip，键 api-body/{app_key}/{yyyyMM}/{event_id}.json.gz；
 * 失败 → 删幂等键 + 503 由 SDK 重发）。
 * <p><b>at-most-once</b>：body 不上离线补发队列（§19 推演雷 6），失败由 SDK 重试；
 * 时间线对缺失 body 显示「body 未采集」占位，诚实口径。
 * <p><b>Redis 降级</b>：与事件摄入同策略——限流/幂等 Redis 故障一律 fail-open 放行并告警，
 * 绝不让辅助通道反噬摄入主链路（幂等失效时对象键覆盖写兜底）。
 */
@Service
public class TrackApiBodyService {

	private static final Logger log = LoggerFactory.getLogger(TrackApiBodyService.class);

	/** 限流分钟窗格式化（键尾缀 yyyyMMddHHmm，与 collect 同窗口语义） */
	private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern(TrackConstants.RATE_LIMIT_MINUTE_PATTERN);
	/** gzip 解压分块大小 */
	private static final int GZIP_CHUNK_BYTES = 8192;

	private final TrackAppService appService;
	private final TrackApiBodyStorage storage;
	private final ParamService paramService;
	private final StringRedisTemplate redis;
	private final TrackIngestMetrics metrics;

	public TrackApiBodyService(TrackAppService appService, TrackApiBodyStorage storage,
							   ParamService paramService, StringRedisTemplate redis, TrackIngestMetrics metrics) {
		this.appService = appService;
		this.storage = storage;
		this.paramService = paramService;
		this.redis = redis;
		this.metrics = metrics;
	}

	/**
	 * 摄入一个接口响应体，返回 {accepted, duplicated}。
	 *
	 * @param root 请求 JSON（已解析、未净化原文）：{app_key, event_id, gzip, payload:&lt;base64&gt;}——
	 *             gzip=true → payload=base64(gzip(响应体原文))；gzip=false → payload=base64(明文)
	 *             （SDK 异步压缩活不过卸载的收尾场景，同 replay 语义）
	 * @param ip   上报端 IP（限流维度）
	 * @throws TrackCollectException 400 协议/应用非法；413 超限；429 限流；503 存储失败（SDK 重发）
	 */
	public Map<String, Object> ingest(JsonNode root, String ip) {
		// 1) 协议字段：app_key 兼作对象键路径段，必须路径安全字符集（防路径穿越）
		String appKey = text(root, TrackConstants.FIELD_APP_KEY);
		if (appKey == null || appKey.length() > TrackConstants.APP_KEY_MAX_LEN
			|| !TrackConstants.REPLAY_PATH_SAFE.matcher(appKey).matches()) {
			throw new TrackCollectException(400, "无效 app_key");
		}
		String eventId = text(root, TrackConstants.FIELD_EVENT_ID);
		if (eventId == null || eventId.length() > TrackConstants.EVENT_ID_MAX_LEN) {
			throw new TrackCollectException(400, "无效 event_id");
		}
		// gzip 缺省按 true（与 replay 同语义）；gzip=false = base64(明文响应体)
		boolean gzipped = root.path("gzip").asBoolean(true);
		String payload = text(root, "payload");
		if (payload == null) {
			throw new TrackCollectException(400, "payload 缺失");
		}
		if (payload.length() > TrackConstants.API_BODY_PAYLOAD_B64_MAX_LEN) {
			throw new TrackCollectException(413, "响应体过大");
		}

		// 2) 应用校验：存在 + enabled=1 + api_body_enabled=1（本地缓存 30s，生效延迟见 TrackAppService javadoc）
		TrackApp app = appService.findCollectable(appKey)
			.orElseThrow(() -> new TrackCollectException(400, "应用不存在或已停用"));
		if (app.getApiBodyEnabled() == null || app.getApiBodyEnabled() != 1) {
			throw new TrackCollectException(400, "应用未开启接口响应体采集");
		}

		// 3) 限流（独立键前缀，阈值 = collect 同级；body 频次远低于事件流但仍需防恶意打爆）
		assertRateLimit(appKey, ip);

		// 4) event_id 路径安全字符集（对象键文件名段，防路径穿越注入对象键）
		if (!TrackConstants.REPLAY_PATH_SAFE.matcher(eventId).matches()) {
			throw new TrackCollectException(400, "无效 event_id");
		}

		// 5) base64 解码 → 明文字节：gzip 体有界解压（防 zip 炸弹，解压后 ≤ sys_param 上限）；
		//    明文体同界长度兜底；落储字节恒为 gzip（明文体服务端补压，存储/读取单一口径）
		long maxBytes = paramLong(TrackConstants.PARAM_API_BODY_MAX_BYTES, TrackConstants.DEFAULT_API_BODY_MAX_BYTES);
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(payload);
		} catch (IllegalArgumentException e) {
			throw new TrackCollectException(400, "payload base64 非法");
		}
		byte[] storeBytes;
		if (gzipped) {
			gunzip(decoded, maxBytes);
			storeBytes = decoded;
		} else {
			if (decoded.length > maxBytes) {
				metrics.dropped("api_body_oversize", 1);
				throw new TrackCollectException(413, "响应体明文超限");
			}
			storeBytes = gzip(decoded);
		}

		// 6) 幂等：同 event_id 重复上传丢弃（跨重发窗口 25h；先于落储，重复写不重复计指标）
		String idemKey = TrackConstants.API_BODY_IDEMPOTENT_KEY_PREFIX + eventId;
		if (!markOnce(idemKey)) {
			metrics.apiBodyDuplicated(1);
			return Map.of("accepted", true, "duplicated", true);
		}

		// 7) 落对象存储（键确定，重写为同键覆盖；失败 → 删幂等键放行重发 + 503，at-most-once 由 SDK 重试兜底）
		try {
			storage.save(appKey, eventId, System.currentTimeMillis(), storeBytes);
		} catch (RuntimeException e) {
			unmark(idemKey);
			metrics.dropped("api_body_store_failed", 1);
			log.warn("响应体落储失败 app={} event={}：{}", appKey, eventId, e.getMessage());
			throw new TrackCollectException(503, "响应体服务繁忙，请重试");
		}
		metrics.apiBodyReceived(1);
		return Map.of("accepted", true, "duplicated", false);
	}

	/** IP+appKey 分钟窗限流（阈值 = collect 参数同级）：INCR 首置 EXPIRE；超限 429；Redis 故障 fail-open */
	private void assertRateLimit(String appKey, String ip) {
		int limit = paramInt(TrackConstants.PARAM_RATE_LIMIT, TrackConstants.DEFAULT_RATE_LIMIT);
		String key = TrackConstants.API_BODY_RATE_LIMIT_KEY_PREFIX + ip + ":" + appKey + ":" + MINUTE_FORMAT.format(LocalDateTime.now());
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
			log.warn("响应体限流计数 Redis 不可用，本次放行：{}", e.getMessage());
		}
	}

	/** 上传幂等：SETNX api-body:{event_id} TTL 25h；Redis 故障 fail-open 放行（对象键覆盖写兜底） */
	private boolean markOnce(String idemKey) {
		try {
			Boolean first = redis.opsForValue().setIfAbsent(idemKey, "1", Duration.ofSeconds(TrackConstants.API_BODY_KEY_TTL_SECONDS));
			return !Boolean.FALSE.equals(first);
		} catch (RuntimeException e) {
			log.warn("响应体幂等键 Redis 不可用，本次放行：{}", e.getMessage());
			return true;
		}
	}

	/** 撤销幂等键（落储失败放行 SDK 重发；尽力而为，失败则该体 25h 内重发被视为重复——概率与代价均可接受） */
	private void unmark(String idemKey) {
		try {
			redis.delete(idemKey);
		} catch (RuntimeException e) {
			log.warn("响应体幂等键撤销失败（该体 25h 内重发将被幂等丢弃）：{}", e.getMessage());
		}
	}

	/** 服务端补压：gzip=false 明文体统一转 gzip 落储（存储/读取恒 gzip 单一口径；单体 ≤1MB，开销可忽略） */
	private byte[] gzip(byte[] raw) {
		try (ByteArrayOutputStream out = new ByteArrayOutputStream();
			 GZIPOutputStream gzip = new GZIPOutputStream(out)) {
			gzip.write(raw);
			gzip.finish();
			return out.toByteArray();
		} catch (IOException e) {
			// ByteArrayOutputStream 实操不抛 IOException，防御兜底
			throw new TrackCollectException(500, "响应体压缩失败");
		}
	}

	/** 有界解压：解压后 ≤ maxBytes 字节（超限 413；gzip 损坏 400） */
	private void gunzip(byte[] raw, long maxBytes) {
		try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(raw));
			 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			byte[] chunk = new byte[GZIP_CHUNK_BYTES];
			long total = 0;
			int n;
			while ((n = gzip.read(chunk)) != -1) {
				total += n;
				if (total > maxBytes) {
					metrics.dropped("api_body_oversize", 1);
					throw new TrackCollectException(413, "响应体解压后超限");
				}
				out.write(chunk, 0, n);
			}
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
