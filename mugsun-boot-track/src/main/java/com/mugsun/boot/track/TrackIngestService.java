package com.mugsun.boot.track;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.mugsun.boot.common.constant.TenantConstants;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.system.service.ParamService;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.track.entity.TrackApp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 摄入同步路径（目标 p95 &lt; 10ms，零 DB 写）：
 * appKey 校验（本地缓存）→ IP+appKey 分钟窗限流（Redis INCR）→ 批量上限截断
 * → 事件级校验（事件名白名单/正则、事件定义停用拒收（G105，本地缓存判定）、props 截断、校时）→ 身份裁定（token 优先，客户端上报值不可信）
 * → Redis 幂等（SETNX 25h）→ tenant_id 服务端裁定 → 实时流 XADD + 在线 ZADD → 入内存有界队列。
 * <p><b>采样</b>：sample_rate 判定在 SDK 侧已做（会话级一致采样），服务端不重复采样——
 * 二次采样会让 rollup 的量级还原（除以采样率）失真。
 * <p><b>Redis 降级</b>：Redis 不可用时幂等/实时流/在线全部 fail-open 跳过并告警
 * （退化为 at-most-once + DB 唯一键同接收窗兜底，§5.2 降级策略），绝不让实时通道反噬摄入主链路。
 */
@Service
public class TrackIngestService {

	private static final Logger log = LoggerFactory.getLogger(TrackIngestService.class);

	/** 限流分钟窗格式化（键尾缀 yyyyMMddHHmm） */
	private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern(TrackConstants.RATE_LIMIT_MINUTE_PATTERN);

	private final TrackAppService appService;
	private final TrackEventDefService eventDefService;
	private final TrackEventConsumer consumer;
	private final ParamService paramService;
	private final StringRedisTemplate redis;
	private final TrackIngestMetrics metrics;

	public TrackIngestService(TrackAppService appService, TrackEventDefService eventDefService,
							  TrackEventConsumer consumer, ParamService paramService,
							  StringRedisTemplate redis, TrackIngestMetrics metrics) {
		this.appService = appService;
		this.eventDefService = eventDefService;
		this.consumer = consumer;
		this.paramService = paramService;
		this.redis = redis;
		this.metrics = metrics;
	}

	/**
	 * 摄入一批事件，返回成功入队条数。
	 *
	 * @param root      批量请求 JSON（已解析、未净化原文）
	 * @param ip        上报端 IP（限流维度 + 属地解析）
	 * @param userAgent 上报端 UA（消费侧解析 browser/os/device）
	 * @throws TrackCollectException 400 协议/应用非法；429 限流
	 */
	public int ingest(JsonNode root, String ip, String userAgent) {
		// 1) appKey 校验：存在且 enabled=1（本地缓存 30s，多副本生效延迟见 TrackAppService javadoc）
		String appKey = text(root, TrackConstants.FIELD_APP_KEY);
		if (appKey == null || appKey.isBlank() || appKey.length() > TrackConstants.APP_KEY_MAX_LEN) {
			throw new TrackCollectException(400, "无效 app_key");
		}
		TrackApp app = appService.findCollectable(appKey)
			.orElseThrow(() -> new TrackCollectException(400, "应用不存在或已停用"));

		// 2) IP+appKey 分钟窗限流（阈值 sys_param 热更；sys_param 平台全局无租户列，getValue 内部已 ignore）
		assertRateLimit(appKey, ip);

		// 3) 批量上限：条数超 batch-max 截断并计数（截断保留队首，后到先丢）
		JsonNode events = root.path(TrackConstants.FIELD_EVENTS);
		if (!events.isArray()) {
			throw new TrackCollectException(400, "events 须为数组");
		}
		int size = events.size();
		int batchMax = paramInt(TrackConstants.PARAM_BATCH_MAX, TrackConstants.DEFAULT_BATCH_MAX);
		int acceptedTarget = Math.min(size, batchMax);
		if (size > batchMax) {
			metrics.dropped("batch_truncated", size - batchMax);
		}

		// 4) 身份裁定（请求级一次解析）：token 有效 → user_id := token 登录 id；客户端上报 user_id 一律忽略
		long receivedAtMs = System.currentTimeMillis();
		Long tokenUserId = tokenUserId();
		String tokenTenant = tokenTenant(tokenUserId);
		String tenantId = resolveTenant(app, tokenTenant);

		// 5) 逐条校验/校时/幂等后入队
		int accepted = 0;
		String platform = text(root.path(TrackConstants.FIELD_SDK), TrackConstants.FIELD_PLATFORM);
		for (int i = 0; i < acceptedTarget; i++) {
			TrackIngestEvent event = processEvent(events.get(i), app, platform, tenantId, tokenUserId,
				receivedAtMs, ip, userAgent);
			if (event == null) {
				continue;
			}
			pushRealtime(event);
			if (consumer.offer(event)) {
				accepted++;
			} else {
				// 队列背压：丢新 + 计数（宁可丢事件不拖垮 DB）
				metrics.dropped("queue_full", 1);
			}
		}
		if (accepted > 0) {
			metrics.received(accepted);
		}
		return accepted;
	}

	/** IP+appKey 分钟窗限流：INCR 首置 EXPIRE 70s；超限抛 429 并计数；Redis 故障 fail-open（降级告警） */
	private void assertRateLimit(String appKey, String ip) {
		int limit = paramInt(TrackConstants.PARAM_RATE_LIMIT, TrackConstants.DEFAULT_RATE_LIMIT);
		String key = TrackConstants.RATE_LIMIT_KEY_PREFIX + ip + ":" + appKey + ":" + MINUTE_FORMAT.format(LocalDateTime.now());
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
			// Redis 不可用降级：限流失效但摄入不停（背压队列仍在兜底）
			log.warn("限流计数 Redis 不可用，本次放行：{}", e.getMessage());
		}
	}

	/** 事件级处理：校验 → 校时 → 身份/租户裁定 → 幂等；任一环不过返回 null（已计数） */
	private TrackIngestEvent processEvent(JsonNode node, TrackApp app, String platform, String tenantId,
										  Long tokenUserId, long receivedAtMs, String ip, String userAgent) {
		if (node == null || !node.isObject()) {
			metrics.dropped("invalid_event", 1);
			return null;
		}
		String eventId = text(node, TrackConstants.FIELD_EVENT_ID);
		String eventName = text(node, TrackConstants.FIELD_EVENT_NAME);
		String distinctId = text(node, TrackConstants.FIELD_DISTINCT_ID);
		String sessionId = text(node, TrackConstants.FIELD_SESSION_ID);
		if (eventId == null || eventId.length() > TrackConstants.EVENT_ID_MAX_LEN
			|| distinctId == null || distinctId.length() > TrackConstants.DISTINCT_ID_MAX_LEN
			|| sessionId == null || sessionId.length() > TrackConstants.SESSION_ID_MAX_LEN) {
			metrics.dropped("invalid_event", 1);
			return null;
		}
		// 事件名：$ 前缀仅白名单（$ 为保留字）；自定义名走正则
		if (eventName == null || eventName.length() > TrackConstants.EVENT_NAME_MAX_LEN || !validEventName(eventName)) {
			metrics.dropped("bad_name", 1);
			return null;
		}
		// 事件定义停用拒收（G105）：无定义/已删 = 未停用（自动注册语义默认启用）；
		// 事件级静默丢弃（批仍 200，与 invalid_event 同语义；不进队列自然不刷 last_seen/不落库）
		if (eventDefService.isDisabled(app.getAppKey(), eventName)) {
			metrics.dropped("event_disabled", 1);
			return null;
		}
		Long clientTs = epochMillis(node.get(TrackConstants.FIELD_TS));
		if (clientTs == null) {
			metrics.dropped("invalid_event", 1);
			return null;
		}

		// 校时（§8.1 统一规则）：荒谬时间（晚于 received_at+7 天 / 早于 2020 年）丢弃；正常偏差不拒收，
		// 超 24h 则 ts := received_at 且 clock_skewed=1，数据照留
		if (clientTs > receivedAtMs + TrackConstants.CLIENT_TS_MAX_FUTURE_MS
			|| clientTs < TrackConstants.CLIENT_TS_MIN_EPOCH_MS) {
			metrics.dropped("ts_absurd", 1);
			return null;
		}
		long ts = clientTs;
		int clockSkewed = 0;
		if (Math.abs(clientTs - receivedAtMs) > TrackConstants.CLOCK_SKEW_THRESHOLD_MS) {
			ts = receivedAtMs;
			clockSkewed = 1;
			metrics.clockSkewed(1);
		}

		// props 截断净化 + 热点属性提升为列
		JsonNode props = node.path(TrackConstants.FIELD_PROPS);
		String propsJson = sanitizeProps(props);

		// Redis 幂等：SETNX 命中即丢 + 计数（跨重发/离线补发窗口 25h；键不含被校时改写的字段）
		if (!markOnce(eventId)) {
			metrics.duplicated(1);
			return null;
		}

		TrackIngestEvent event = new TrackIngestEvent();
		event.setEventId(eventId);
		event.setAppKey(app.getAppKey());
		event.setEventName(eventName);
		event.setClientTsMs(clientTs);
		event.setTsMs(ts);
		event.setReceivedAtMs(receivedAtMs);
		event.setClockSkewed(clockSkewed);
		event.setDistinctId(distinctId);
		event.setSessionId(sessionId);
		event.setTenantId(tenantId);
		event.setUserId(tokenUserId);
		event.setUrlPath(truncate(text(props, TrackConstants.PROP_URL_PATH), TrackConstants.URL_MAX_LEN));
		event.setRoutePath(truncate(text(props, TrackConstants.PROP_ROUTE_PATH), TrackConstants.DIM_MAX_LEN));
		event.setPageTitle(truncate(text(props, TrackConstants.PROP_PAGE_TITLE), TrackConstants.PAGE_TITLE_MAX_LEN));
		event.setReferrerDomain(truncate(text(props, TrackConstants.PROP_REFERRER_DOMAIN), TrackConstants.DIM_MAX_LEN));
		event.setUtmSource(truncate(text(props, TrackConstants.PROP_UTM_SOURCE), TrackConstants.DIM_MAX_LEN));
		event.setUtmMedium(truncate(text(props, TrackConstants.PROP_UTM_MEDIUM), TrackConstants.DIM_MAX_LEN));
		event.setUtmCampaign(truncate(text(props, TrackConstants.PROP_UTM_CAMPAIGN), TrackConstants.DIM_MAX_LEN));
		event.setDurationMs(intValue(props.get(TrackConstants.PROP_DURATION_MS)));
		event.setDevice(truncate(text(props, TrackConstants.PROP_DEVICE), TrackConstants.DEVICE_MAX_LEN));
		event.setErrorFingerprint(resolveFingerprint(eventName, props));
		event.setPropsJson(propsJson);
		double[] geo = TrackGeo.parseLonLat(props);
		if (geo != null) {
			event.setGeoLon(geo[0]);
			event.setGeoLat(geo[1]);
		}
		event.setIp(ip);
		event.setUserAgent(userAgent);
		event.setPlatform(platform);

		// $identify 绑定裁定：仅当 token 有效且 token.user_id == props.user_id 才标记待绑（消费侧落 track_identity）；
		// 不满足则只记事件不建映射 + 计数（防伪造 identify 污染他人画像）
		if (TrackConstants.EVENT_IDENTIFY.equals(eventName)) {
			String claimed = props.path(TrackConstants.FIELD_USER_ID).asText(null);
			if (tokenUserId != null && String.valueOf(tokenUserId).equals(claimed)) {
				event.setIdentifyUserId(tokenUserId);
			} else {
				metrics.identityRejected(tokenUserId == null ? "identify_no_token" : "identify_user_mismatch");
			}
		}
		return event;
	}

	/** 事件名合法性：$ 前缀仅白名单；自定义名 ^[A-Za-z][A-Za-z0-9_]{0,63}$ */
	private boolean validEventName(String eventName) {
		if (eventName.startsWith("$")) {
			return TrackConstants.PREDEFINED_EVENTS.contains(eventName);
		}
		return TrackConstants.CUSTOM_EVENT_NAME.matcher(eventName).matches();
	}

	/** 租户裁定：恒非空。token 租户与 app_key 映射租户不一致时以 token 为准并计数告警；客户端上报值一律丢弃 */
	private String resolveTenant(TrackApp app, String tokenTenant) {
		if (tokenTenant != null && !tokenTenant.isBlank()) {
			if (app.getTenantId() != null && !tokenTenant.equals(app.getTenantId())) {
				metrics.identityRejected("token_tenant_mismatch");
			}
			return tokenTenant;
		}
		return app.getTenantId() != null && !app.getTenantId().isBlank()
			? app.getTenantId() : TenantConstants.DEFAULT_TENANT_ID;
	}

	/** token 登录 id（无效/过期/无 token 一律 null；匿名采集不因此失败） */
	private Long tokenUserId() {
		try {
			Object loginId = StpUtil.getLoginIdDefaultNull();
			return loginId == null ? null : Long.parseLong(loginId.toString());
		} catch (Exception e) {
			return null;
		}
	}

	/** token 会话租户（登录时写入；无会话/缺失返回 null） */
	private String tokenTenant(Long tokenUserId) {
		if (tokenUserId == null) {
			return null;
		}
		try {
			Object tenant = StpUtil.getSession().get(TenantContext.TENANT_SESSION_KEY);
			return tenant == null ? null : tenant.toString();
		} catch (Exception e) {
			return null;
		}
	}

	/** 事件幂等：SETNX evt:{event_id} TTL 25h；Redis 故障 fail-open 放行（DB 唯一键同接收窗兜底） */
	private boolean markOnce(String eventId) {
		try {
			Boolean first = redis.opsForValue().setIfAbsent(TrackConstants.IDEMPOTENT_KEY_PREFIX + eventId,
				"1", Duration.ofSeconds(TrackConstants.IDEMPOTENT_TTL_SECONDS));
			return !Boolean.FALSE.equals(first);
		} catch (RuntimeException e) {
			log.warn("幂等键 Redis 不可用，本次放行（DB 唯一键兜底）：{}", e.getMessage());
			return true;
		}
	}

	/** 实时流 XADD（MAXLEN ~1000 近似裁剪）+ 在线 ZADD（score=接收毫秒）；尽力而为，失败不影响摄入 */
	private void pushRealtime(TrackIngestEvent event) {
		try {
			Map<String, String> payload = new HashMap<>();
			payload.put(TrackConstants.FIELD_EVENT_ID, event.getEventId());
			payload.put(TrackConstants.FIELD_EVENT_NAME, event.getEventName());
			payload.put(TrackConstants.FIELD_TS, String.valueOf(event.getTsMs()));
			payload.put(TrackConstants.FIELD_DISTINCT_ID, event.getDistinctId());
			payload.put(TrackConstants.FIELD_SESSION_ID, event.getSessionId());
			if (event.getUserId() != null) {
				payload.put(TrackConstants.FIELD_USER_ID, String.valueOf(event.getUserId()));
			}
			if (event.getUrlPath() != null) {
				payload.put(TrackConstants.PROP_URL_PATH, event.getUrlPath());
			}
			String streamKey = TrackConstants.STREAM_KEY_PREFIX + event.getAppKey();
			redis.opsForStream().add(StreamRecords.mapBacked(payload).withStreamKey(streamKey));
			redis.opsForStream().trim(streamKey, TrackConstants.STREAM_MAX_LEN, true);
			redis.opsForZSet().add(TrackConstants.ONLINE_KEY_PREFIX + event.getAppKey(),
				event.getSessionId(), event.getReceivedAtMs());
		} catch (RuntimeException e) {
			log.warn("实时流/在线写入 Redis 不可用，跳过（落库主链路不受影响）：{}", e.getMessage());
		}
	}

	/** props 截断净化：键 ≤64 / 值 ≤1024 / 嵌套深度 ≤3（超出剪为 null）/ 单事件总量 ≤16KB（超出从尾部逐键摘除） */
	private String sanitizeProps(JsonNode props) {
		if (props == null || !props.isObject()) {
			return "{}";
		}
		ObjectNode out = JsonNodeFactory.instance.objectNode();
		Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> field = fields.next();
			String key = truncate(field.getKey(), TrackConstants.PROPS_KEY_MAX_LEN);
			if (key == null || key.isBlank()) {
				continue;
			}
			// 同键（截断后撞键）保留先出现者
			if (!out.has(key)) {
				out.set(key, sanitizeValue(field.getValue(), 1));
			}
		}
		String json = out.toString();
		while (json.getBytes(StandardCharsets.UTF_8).length > TrackConstants.PROPS_TOTAL_MAX_BYTES && out.size() > 0) {
			String last = null;
			Iterator<String> names = out.fieldNames();
			while (names.hasNext()) {
				last = names.next();
			}
			out.remove(last);
			json = out.toString();
		}
		return json;
	}

	/** 值截断：字符串 ≤1024；对象/数组超深度（>3 层）剪为 null；数值/布尔/空原样保留 */
	private JsonNode sanitizeValue(JsonNode node, int depth) {
		if (node == null || node.isNull()) {
			return NullNode.getInstance();
		}
		if (node.isTextual()) {
			return TextNode.valueOf(truncate(node.asText(), TrackConstants.PROPS_VALUE_MAX_LEN));
		}
		if (node.isObject()) {
			if (depth >= TrackConstants.PROPS_MAX_DEPTH) {
				return NullNode.getInstance();
			}
			ObjectNode child = JsonNodeFactory.instance.objectNode();
			Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
			while (fields.hasNext()) {
				Map.Entry<String, JsonNode> field = fields.next();
				String key = truncate(field.getKey(), TrackConstants.PROPS_KEY_MAX_LEN);
				if (key != null && !key.isBlank() && !child.has(key)) {
					child.set(key, sanitizeValue(field.getValue(), depth + 1));
				}
			}
			return child;
		}
		if (node.isArray()) {
			if (depth >= TrackConstants.PROPS_MAX_DEPTH) {
				return NullNode.getInstance();
			}
			ArrayNode child = JsonNodeFactory.instance.arrayNode();
			for (JsonNode item : node) {
				child.add(sanitizeValue(item, depth + 1));
			}
			return child;
		}
		return node;
	}

	/** 错误指纹：SDK 已算好（props.error_fingerprint）直接用；否则按 message+堆栈首行 SHA-256 兜底（错误分组聚合用） */
	private String resolveFingerprint(String eventName, JsonNode props) {
		if (!TrackConstants.EVENT_ERROR.equals(eventName)) {
			return null;
		}
		String reported = truncate(text(props, TrackConstants.PROP_ERROR_FINGERPRINT), TrackConstants.ERROR_FINGERPRINT_MAX_LEN);
		if (reported != null) {
			return reported;
		}
		String message = text(props, TrackConstants.PROP_ERROR_MESSAGE);
		if (message == null) {
			return null;
		}
		String stack = text(props, TrackConstants.PROP_ERROR_STACK);
		String firstFrame = stack == null ? "" : stack.split("\\R", 2)[0];
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest((message + "\n" + firstFrame).getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			return null;
		}
	}

	/** sys_param 取整（缺失/非法/超界回退兜底默认；下限 1） */
	private int paramInt(String key, int defaultValue) {
		String value = paramService.getValue(key);
		if (value == null) {
			return defaultValue;
		}
		try {
			int parsed = Integer.parseInt(value.trim());
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

	/** epoch 毫秒字段（数值或可解析文本；其余归 null） */
	private Long epochMillis(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		if (node.isNumber()) {
			return node.asLong();
		}
		if (node.isTextual()) {
			try {
				return Long.parseLong(node.asText().trim());
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	/** 整数字段（数值且在 int 值域；其余归 null） */
	private Integer intValue(JsonNode node) {
		if (node == null || !node.isNumber()) {
			return null;
		}
		long value = node.asLong();
		return value > Integer.MAX_VALUE || value < Integer.MIN_VALUE ? null : (int) value;
	}

	private String truncate(String s, int max) {
		if (s == null) {
			return null;
		}
		return s.length() > max ? s.substring(0, max) : s;
	}
}
