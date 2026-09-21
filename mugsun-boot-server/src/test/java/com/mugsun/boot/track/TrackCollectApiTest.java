package com.mugsun.boot.track;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.system.entity.SysUser;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.system.service.ParamService;
import com.mugsun.boot.tenant.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.mybatisflex.core.datasource.DataSourceKey;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 埋点摄入链路集成测试（G99 B2）：/track/collect 校验、限流、校时、幂等、身份裁定、$identify 绑定、
 * 会话乱序安全 upsert、混合租户批写、事件名治理、实时流/在线 ZSET、/track/config 下发。
 * <p>track 数据源与上下文共享见 {@link AbstractTrackIntegrationTest}；消费器异步落库，
 * 断言一律 await 轮询（≤15s），负向断言用「同批标记事件落库后核对」避免长 sleep。
 */
class TrackCollectApiTest extends AbstractTrackIntegrationTest {

	/** 测试应用（@BeforeEach 幂等播种）：A=租户 000000，B=租户 T00002（混合租户用），RL=限流专用，OFF=停用 */
	private static final String APP_A = "it-app-a";
	private static final String APP_B = "it-app-b";
	private static final String APP_RL = "it-app-rl";
	private static final String APP_OFF = "it-app-off";
	private static final String APP_B_TENANT = "T00002";
	/** 共享设备用例的第二用户 */
	private static final String USER_B = "track_it_user_b";

	@Autowired
	private ParamService paramService;
	@Autowired
	private StringRedisTemplate redis;
	@Autowired
	private SysUserMapper userMapper;
	@Autowired
	private PasswordEncoder passwordEncoder;
	@Autowired
	private MeterRegistry meterRegistry;

	/** 播种测试应用（幂等；track_app 在埋点库，经 DataSourceKey 路由） */
	@BeforeEach
	void seedApps() {
		seedApp(APP_A, PLATFORM_TENANT, 1);
		seedApp(APP_B, APP_B_TENANT, 1);
		seedApp(APP_RL, PLATFORM_TENANT, 1);
		seedApp(APP_OFF, PLATFORM_TENANT, 0);
	}

	private void seedApp(String appKey, String tenantId, int enabled) {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(
			"INSERT INTO track_app (id, app_key, app_name, platform, tenant_id, sample_rate, enabled,"
				+ " create_time, update_time, is_deleted) VALUES (?, ?, ?, 'web', ?, 100, ?, now(), now(), 0)"
				+ " ON CONFLICT (app_key) WHERE is_deleted = 0 DO UPDATE SET enabled = EXCLUDED.enabled",
			IdUtil.getSnowflakeNextId(), appKey, "集成测试-" + appKey, tenantId, enabled));
	}

	/** ① 批量接收落库：行数 + 字段映射（url_path/route_path/utm/tenant_id 服务端映射、user_id 空、三时间戳） */
	@Test
	void batchCollectPersistsWithColumnMapping() {
		String sessionId = uuid();
		String distinctId = uuid();
		long clientTs = System.currentTimeMillis();
		List<Map<String, Object>> events = new ArrayList<>();
		String e1 = uuid();
		events.add(event(e1, "$pageview", clientTs, distinctId, sessionId, Map.of(
			"url_path", "/home", "route_path", "/home", "page_title", "首页",
			"referrer_domain", "google.com", "utm_source", "google", "utm_medium", "cpc", "utm_campaign", "sale")));
		events.add(event(uuid(), "$click", clientTs + 100, distinctId, sessionId, Map.of("url_path", "/home")));
		events.add(event(uuid(), "signup_click", clientTs + 200, distinctId, sessionId, Map.of("plan", "pro")));

		ResponseEntity<String> resp = post("/track/collect", payload(APP_A, events), null);
		assertThat(resp.getStatusCode().value()).isEqualTo(200);
		JsonNode body = readBody(resp);
		assertThat(body.path("code").asInt()).isEqualTo(200);
		assertThat(body.path("data").path("received").asInt()).isEqualTo(3);

		awaitUntil("3 条事件落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE app_key = ? AND session_id = ?", APP_A, sessionId) == 3L);

		Row row = trackRow("SELECT url_path, route_path, page_title, referrer_domain, utm_source, utm_medium,"
			+ " utm_campaign, tenant_id, user_id, clock_skewed, received_at,"
			+ " CAST(EXTRACT(EPOCH FROM ts) * 1000 AS BIGINT) AS ts_ms"
			+ " FROM track_event WHERE event_id = ?", e1);
		assertThat(row.getString("url_path")).isEqualTo("/home");
		assertThat(row.getString("route_path")).isEqualTo("/home");
		assertThat(row.getString("page_title")).isEqualTo("首页");
		assertThat(row.getString("referrer_domain")).isEqualTo("google.com");
		assertThat(row.getString("utm_source")).isEqualTo("google");
		assertThat(row.getString("utm_medium")).isEqualTo("cpc");
		assertThat(row.getString("utm_campaign")).isEqualTo("sale");
		assertThat(row.getString("tenant_id")).as("tenant_id 服务端映射，恒非空").isEqualTo(PLATFORM_TENANT);
		assertThat(row.get("user_id")).as("匿名事件 user_id 应为 NULL").isNull();
		assertThat(row.getInt("clock_skewed")).isEqualTo(0);
		assertThat(row.get("received_at")).as("received_at 服务端接收时间非空").isNotNull();
		assertThat(row.getLong("ts_ms")).as("无偏差时 ts 等于 client_ts").isEqualTo(clientTs);
	}

	/** ② 伪 app_key / 停用应用 4xx（不存在的 key 走负缓存，不打库风暴） */
	@Test
	void fakeAppKeyRejected() {
		ResponseEntity<String> fake = post("/track/collect",
			payload("no-such-app", List.of(event(uuid(), "$pageview", System.currentTimeMillis(), uuid(), uuid(), Map.of()))), null);
		assertThat(fake.getStatusCode().value()).isEqualTo(400);
		assertThat(readBody(fake).path("code").asInt()).isEqualTo(400);

		ResponseEntity<String> disabled = post("/track/collect",
			payload(APP_OFF, List.of(event(uuid(), "$pageview", System.currentTimeMillis(), uuid(), uuid(), Map.of()))), null);
		assertThat(disabled.getStatusCode().value()).as("停用应用应拒收").isEqualTo(400);
	}

	/** ③ 限流：阈值调小后连续超限 → 后续批次 429（键含 IP+appKey 分钟窗，不影响其他用例） */
	@Test
	void rateLimitRejectsOverflow() {
		paramService.setValue(TrackConstants.PARAM_RATE_LIMIT, "2");
		try {
			for (int i = 0; i < 2; i++) {
				ResponseEntity<String> ok = post("/track/collect",
					payload(APP_RL, List.of(event(uuid(), "$pageview", System.currentTimeMillis(), uuid(), uuid(), Map.of()))), null);
				assertThat(ok.getStatusCode().value()).as("第 %d 批应放行", i + 1).isEqualTo(200);
			}
			ResponseEntity<String> limited = post("/track/collect",
				payload(APP_RL, List.of(event(uuid(), "$pageview", System.currentTimeMillis(), uuid(), uuid(), Map.of()))), null);
			assertThat(limited.getStatusCode().value()).as("超限批应 429").isEqualTo(429);
		} finally {
			paramService.setValue(TrackConstants.PARAM_RATE_LIMIT, String.valueOf(TrackConstants.DEFAULT_RATE_LIMIT));
		}
	}

	/** ④ 校时：偏 2 天 → ts≈received_at 且 clock_skewed=1；荒谬时间（+10 天）→ 丢弃 */
	@Test
	void clockSkewCorrectedAndAbsurdDropped() {
		long now = System.currentTimeMillis();
		String skewedId = uuid();
		String absurdId = uuid();
		String markerId = uuid();
		String sessionId = uuid();
		// 同批：偏 2 天事件 + 荒谬时间事件（+10 天）+ 正常标记事件（证明批次已被消费）
		List<Map<String, Object>> events = List.of(
			event(skewedId, "$pageview", now - 2L * 24 * 3600 * 1000, uuid(), sessionId, Map.of("url_path", "/skew")),
			event(absurdId, "$pageview", now + 10L * 24 * 3600 * 1000, uuid(), sessionId, Map.of("url_path", "/absurd")),
			event(markerId, "$click", now, uuid(), sessionId, Map.of("url_path", "/marker")));
		ResponseEntity<String> resp = post("/track/collect", payload(APP_A, events), null);
		assertThat(readBody(resp).path("data").path("received").asInt())
			.as("荒谬时间事件同步丢弃，仅 2 条入队").isEqualTo(2);

		awaitUntil("标记事件落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE event_id = ?", markerId) == 1L);
		// 双消费线程下校时事件与标记事件可能分属不同批次（落库先后不定），await 校时事件落库再读
		awaitUntil("校时事件落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE event_id = ?", skewedId) == 1L);

		Row skewed = trackRow("SELECT clock_skewed,"
			+ " CAST(EXTRACT(EPOCH FROM ts) * 1000 AS BIGINT) AS ts_ms,"
			+ " CAST(EXTRACT(EPOCH FROM received_at) * 1000 AS BIGINT) AS received_ms"
			+ " FROM track_event WHERE event_id = ?", skewedId);
		assertThat(skewed.getInt("clock_skewed")).as("偏差 2 天应发生校时修正").isEqualTo(1);
		assertThat(Math.abs(skewed.getLong("ts_ms") - skewed.getLong("received_ms")))
			.as("校时后 ts 应贴近 received_at").isLessThanOrEqualTo(5000L);

		assertThat(trackLong("SELECT count(*) AS c FROM track_event WHERE event_id = ?", absurdId))
			.as("荒谬时间事件应丢弃不落库").isEqualTo(0L);
	}

	/** ⑤ 重复 event_id 重发：Redis SETNX 命中即丢，仅一行 */
	@Test
	void duplicateEventIdDropped() {
		String eventId = uuid();
		String sessionId = uuid();
		Map<String, Object> e = event(eventId, "$click", System.currentTimeMillis(), uuid(), sessionId, Map.of());
		ResponseEntity<String> first = post("/track/collect", payload(APP_A, List.of(e)), null);
		assertThat(readBody(first).path("data").path("received").asInt()).isEqualTo(1);
		awaitUntil("首发落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE event_id = ?", eventId) == 1L);

		ResponseEntity<String> second = post("/track/collect", payload(APP_A, List.of(e)), null);
		assertThat(readBody(second).path("data").path("received").asInt())
			.as("重发应被幂等丢弃（同步路径 0 入队）").isEqualTo(0);
		assertThat(trackLong("SELECT count(*) AS c FROM track_event WHERE event_id = ?", eventId))
			.as("同 event_id 仅一行").isEqualTo(1L);
	}

	/** ⑥ 身份裁定：带 token 事件 user_id=token 用户（客户端上报他人 user_id 被忽略）；无 token 为 NULL */
	@Test
	void identityArbitratedByToken() {
		String token = loginAdmin();
		Long adminId = currentUserId(ADMIN_USERNAME);

		long now = System.currentTimeMillis();
		// 带 token 请求：客户端伪造 user_id=999999999，应被覆盖为 token 用户
		String authedId = uuid();
		ResponseEntity<String> authedResp = post("/track/collect", payload(APP_A, List.of(
			event(authedId, "$click", now, uuid(), uuid(), Map.of(), 999999999L))), token);
		assertThat(readBody(authedResp).path("data").path("received").asInt()).as("带token批应入队 1 条").isEqualTo(1);
		// 无 token 请求：客户端伪造 user_id 一律忽略，落 NULL
		String anonId = uuid();
		ResponseEntity<String> anonResp = post("/track/collect", payload(APP_A, List.of(
			event(anonId, "$click", now, uuid(), uuid(), Map.of(), 999999999L))), null);
		assertThat(readBody(anonResp).path("data").path("received").asInt()).as("匿名批应入队 1 条").isEqualTo(1);

		awaitUntil("两条事件落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE event_id IN (?, ?)", authedId, anonId) == 2L);
		Row authed = trackRow("SELECT event_id, user_id FROM track_event WHERE event_id = ?", authedId);
		assertThat(authed).as("带token事件应落库: " + authedId).isNotNull();
		assertThat(authed.getLong("user_id")).as("user_id 应裁定为 token 用户").isEqualTo(adminId);
		Row anon = trackRow("SELECT event_id, user_id FROM track_event WHERE event_id = ?", anonId);
		assertThat(anon).as("匿名事件应落库: " + anonId).isNotNull();
		assertThat(anon.get("user_id")).as("无 token 事件 user_id 应为 NULL（客户端伪造值忽略）").isNull();
	}

	/** ⑦ $identify：无 token 不落映射；token 且一致 → 落；token 不一致 → 不落（同批标记事件确认已消费） */
	@Test
	void identifyBindingDiscipline() {
		String token = loginAdmin();
		Long adminId = currentUserId(ADMIN_USERNAME);

		// 无 token：只记事件不建映射
		String noTokenDistinct = uuid();
		String marker1 = uuid();
		post("/track/collect", payload(APP_A, List.of(
			identifyEvent(uuid(), noTokenDistinct, uuid(), adminId),
			event(marker1, "$click", System.currentTimeMillis(), noTokenDistinct, uuid(), Map.of()))), null);
		awaitUntil("无token标记事件落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE event_id = ?", marker1) == 1L);
		assertThat(identityCount(noTokenDistinct)).as("无 token 的 identify 不应落 track_identity").isEqualTo(0L);

		// token 且 props.user_id == token 用户：落映射
		String okDistinct = uuid();
		post("/track/collect", payload(APP_A, List.of(
			identifyEvent(uuid(), okDistinct, uuid(), adminId))), token);
		awaitUntil("一致 identify 落映射", () -> identityCount(okDistinct) == 1L);
		Row bound = trackRow("SELECT user_id FROM track_identity WHERE app_key = ? AND distinct_id = ?", APP_A, okDistinct);
		assertThat(bound.getLong("user_id")).isEqualTo(adminId);

		// token 但 props.user_id 为他人：只记事件不建映射
		String mismatchDistinct = uuid();
		String marker2 = uuid();
		post("/track/collect", payload(APP_A, List.of(
			identifyEvent(uuid(), mismatchDistinct, uuid(), 999999999L),
			event(marker2, "$click", System.currentTimeMillis(), mismatchDistinct, uuid(), Map.of()))), token);
		awaitUntil("不一致标记事件落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE event_id = ?", marker2) == 1L);
		assertThat(identityCount(mismatchDistinct)).as("token 与 identify 值不一致不应落映射").isEqualTo(0L);
	}

	/** ⑧ 共享设备：同 distinct_id 先后两个有效 token identify 不同用户 → user_id 仍为首绑（不覆盖） */
	@Test
	void identityFirstBindNeverOverwritten() {
		String tokenA = loginAdmin();
		Long adminId = currentUserId(ADMIN_USERNAME);
		Long userBId = ensureUserB();
		String tokenB = login(PLATFORM_TENANT, USER_B, ADMIN_PASSWORD);

		String distinctId = uuid();
		post("/track/collect", payload(APP_A, List.of(identifyEvent(uuid(), distinctId, uuid(), adminId))), tokenA);
		awaitUntil("首绑落库", () -> identityCount(distinctId) == 1L);

		// 第二用户同设备 identify：同批标记事件确认消费后再断言首绑未被覆盖
		String marker = uuid();
		post("/track/collect", payload(APP_A, List.of(
			identifyEvent(uuid(), distinctId, uuid(), userBId),
			event(marker, "$click", System.currentTimeMillis(), distinctId, uuid(), Map.of()))), tokenB);
		awaitUntil("二次 identify 批次已消费", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE event_id = ?", marker) == 1L);

		Row identity = trackRow("SELECT user_id FROM track_identity WHERE app_key = ? AND distinct_id = ?",
			APP_A, distinctId);
		assertThat(identity.getLong("user_id")).as("共享设备首绑不覆盖：仍归首个绑定用户").isEqualTo(adminId);
	}

	/** ⑨ 会话 upsert 乱序安全：先发晚事件再发早事件 → end_time/exit_path 不回退，start_time/entry_path 前移 */
	@Test
	void sessionUpsertOutOfOrderSafe() {
		String sessionId = uuid();
		String distinctId = uuid();
		long base = System.currentTimeMillis();
		long lateTs = base - 60000L;
		long earlyTs = base - 300000L;

		post("/track/collect", payload(APP_A, List.of(
			event(uuid(), "$pageview", lateTs, distinctId, sessionId, Map.of("url_path", "/b")))), null);
		awaitUntil("首事件会话落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_session WHERE session_id = ?", sessionId) == 1L);

		post("/track/collect", payload(APP_A, List.of(
			event(uuid(), "$pageview", earlyTs, distinctId, sessionId, Map.of("url_path", "/a")))), null);
		awaitUntil("乱序早事件并入会话", () -> {
			Row r = trackRow("SELECT event_count FROM track_session WHERE session_id = ?", sessionId);
			return r != null && r.getInt("event_count") == 2;
		});

		Row s = trackRow("SELECT start_time, end_time, entry_path, exit_path, pageviews, event_count, duration_ms"
			+ " FROM track_session WHERE session_id = ?", sessionId);
		assertThat(toLdt(s.get("end_time"))).as("end_time 不回退")
			.isEqualTo(LocalDateTime.ofInstant(Instant.ofEpochMilli(lateTs), ZoneOffset.UTC));
		assertThat(toLdt(s.get("start_time"))).as("start_time 前移取 LEAST")
			.isEqualTo(LocalDateTime.ofInstant(Instant.ofEpochMilli(earlyTs), ZoneOffset.UTC));
		assertThat(s.getString("exit_path")).as("exit_path 不被旧事件覆盖").isEqualTo("/b");
		assertThat(s.getString("entry_path")).as("entry_path 被更早事件前移").isEqualTo("/a");
		assertThat(s.getInt("pageviews")).isEqualTo(2);
		assertThat(s.getInt("event_count")).isEqualTo(2);
		assertThat(s.getInt("duration_ms")).as("duration=end-start").isBetween(239000, 241000);
	}

	/** ⑩ 混合租户批写不串：两个应用（不同映射租户）相继摄入，各行自带 app_key 映射租户 */
	@Test
	void mixedTenantBatchNotCrossed() {
		String eventA = uuid();
		String eventB = uuid();
		post("/track/collect", payload(APP_A, List.of(
			event(eventA, "$click", System.currentTimeMillis(), uuid(), uuid(), Map.of("tenant_id", APP_B_TENANT)))), null);
		post("/track/collect", payload(APP_B, List.of(
			event(eventB, "$click", System.currentTimeMillis(), uuid(), uuid(), Map.of("tenant_id", PLATFORM_TENANT)))), null);

		awaitUntil("两应用事件均落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE event_id IN (?, ?)", eventA, eventB) == 2L);
		Row a = trackRow("SELECT tenant_id FROM track_event WHERE event_id = ?", eventA);
		Row b = trackRow("SELECT tenant_id FROM track_event WHERE event_id = ?", eventB);
		assertThat(a.getString("tenant_id")).as("A 应用事件归其映射租户（客户端伪造被丢弃）").isEqualTo(PLATFORM_TENANT);
		assertThat(b.getString("tenant_id")).as("B 应用事件归其映射租户（客户端伪造被丢弃）").isEqualTo(APP_B_TENANT);
	}

	/** ⑪ 事件名治理：未知 $ 前缀拒收；自定义事件自动注册进 track_event_def（first_seen 非空） */
	@Test
	void eventNameGovernance() {
		String hackId = uuid();
		String customId = uuid();
		long now = System.currentTimeMillis();
		ResponseEntity<String> resp = post("/track/collect", payload(APP_A, List.of(
			event(hackId, "$hack", now, uuid(), uuid(), Map.of()),
			event(customId, "signup_click", now + 1, uuid(), uuid(), Map.of("plan", "pro")))), null);
		assertThat(readBody(resp).path("data").path("received").asInt())
			.as("未知 $ 事件拒收，仅自定义事件入队").isEqualTo(1);

		awaitUntil("自定义事件落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE event_id = ?", customId) == 1L);
		assertThat(trackLong("SELECT count(*) AS c FROM track_event WHERE event_id = ?", hackId))
			.as("未知 $ 前缀事件不落库").isEqualTo(0L);
		awaitUntil("自定义事件自动注册", () -> trackLong(
			"SELECT count(*) AS c FROM track_event_def WHERE app_key = ? AND event_name = 'signup_click'"
				+ " AND first_seen_time IS NOT NULL", APP_A) == 1L);
	}

	/** ⑫ 实时流/在线：collect 后 Redis Stream 有尾数据、在线 ZSET 有会话成员 */
	@Test
	void realtimeStreamAndOnlinePopulated() {
		String sessionId = uuid();
		post("/track/collect", payload(APP_B, List.of(
			event(uuid(), "$pageview", System.currentTimeMillis(), uuid(), sessionId, Map.of("url_path", "/rt")))), null);
		awaitUntil("实时流有数据", () -> {
			Long size = redis.opsForStream().size(TrackConstants.STREAM_KEY_PREFIX + APP_B);
			return size != null && size > 0;
		});
		Long online = redis.opsForZSet().zCard(TrackConstants.ONLINE_KEY_PREFIX + APP_B);
		assertThat(online).as("在线 ZSET 应有会话成员").isNotNull().isGreaterThanOrEqualTo(1L);
	}

	/** ⑬ 配置下发：enabled/sampleRate/maskSelectors + replayEnabled 恒 false；未知应用 400 */
	@Test
	void configEndpoint() {
		ResponseEntity<String> resp = get("/track/config?app_key=" + APP_A, null);
		assertThat(resp.getStatusCode().value()).isEqualTo(200);
		JsonNode data = readBody(resp).path("data");
		assertThat(data.path("enabled").asBoolean()).isTrue();
		assertThat(data.path("sampleRate").asInt()).isEqualTo(100);
		assertThat(data.path("replayEnabled").asBoolean()).as("回放 G100 才开，本期恒 false").isFalse();
		assertThat(data.has("maskSelectors")).isTrue();
		assertThat(data.has("replaySampleRate")).isTrue();
		assertThat(data.path("geoEnabled").asBoolean()).as("G106 默认关").isFalse();

		ResponseEntity<String> unknown = get("/track/config?app_key=no-such-app", null);
		assertThat(unknown.getStatusCode().value()).isEqualTo(400);
	}

	/** 畸形 JSON → 400（不落入全局 500 兜底刷错误日志；Checker 观察项修复回归） */
	@Test
	void malformedJsonRejected400() {
		ResponseEntity<String> resp = post("/track/collect", "{not-a-json", null);
		assertThat(resp.getStatusCode().value()).isEqualTo(400);
		assertThat(readBody(resp).path("code").asInt()).isEqualTo(400);
	}

	/** ⑭ 事件定义停用拒收（G105）：停用 → 摄入 200 但零落库（dropped reason=event_disabled 计数）；
	 *  启用恢复落库；eventDefSubmit 后 evict 即时生效（不等 30s TTL） */
	@Test
	void eventDefDisabledRejected() {
		String adminToken = loginAdmin();
		String sessionId = uuid();
		String disabledName = "g105_off_evt";
		long now = System.currentTimeMillis();

		// 未停用（定义尚不存在，自动注册语义默认启用）：摄入落库 + 自动注册定义 + 暖「未停用」缓存（30s TTL）
		String firstId = uuid();
		post("/track/collect", payload(APP_A, List.of(
			event(firstId, disabledName, now, uuid(), sessionId, Map.of()))), null);
		awaitUntil("未停用事件落库并自动注册定义", () -> trackLong(
			"SELECT count(*) AS c FROM track_event_def WHERE app_key = ? AND event_name = ?", APP_A, disabledName) == 1L);
		assertThat(trackLong("SELECT count(*) AS c FROM track_event WHERE event_id = ?", firstId))
			.as("未停用事件应落库").isEqualTo(1L);

		// 管理端停用（eventDefSubmit 改 status=0；evict 即时失效采集端缓存，不等 TTL）
		long defId = trackRow("SELECT id FROM track_event_def WHERE app_key = ? AND event_name = ?",
			APP_A, disabledName).getLong("id");
		double droppedBefore = droppedCount("event_disabled");
		Map<String, Object> submit = new HashMap<>();
		submit.put("id", defId);
		submit.put("status", 0);
		ResponseEntity<String> submitResp = post("/system/track/event-def/submit", submit, adminToken);
		assertThat(readBody(submitResp).path("code").asInt())
			.as("停用提交应成功：" + submitResp.getBody()).isEqualTo(200);

		// 停用后摄入：批仍 200（同批标记事件入队落库），停用事件静默丢弃零落库 + dropped 计数 +1
		String disabledId = uuid();
		String marker = uuid();
		ResponseEntity<String> resp = post("/track/collect", payload(APP_A, List.of(
			event(disabledId, disabledName, now + 1000, uuid(), sessionId, Map.of()),
			event(marker, "$click", now + 1001, uuid(), sessionId, Map.of()))), null);
		assertThat(resp.getStatusCode().value()).isEqualTo(200);
		assertThat(readBody(resp).path("data").path("received").asInt())
			.as("停用事件静默丢弃，仅标记事件入队").isEqualTo(1);
		awaitUntil("标记事件落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE event_id = ?", marker) == 1L);
		assertThat(trackLong("SELECT count(*) AS c FROM track_event WHERE event_id = ?", disabledId))
			.as("停用事件应零落库").isEqualTo(0L);
		assertThat(droppedCount("event_disabled") - droppedBefore)
			.as("dropped(reason=event_disabled) 计数应 +1").isEqualTo(1.0);

		// 恢复启用（再次 evict 即时生效）：同名事件恢复落库
		submit.put("status", 1);
		assertThat(readBody(post("/system/track/event-def/submit", submit, adminToken)).path("code").asInt())
			.as("恢复启用提交应成功").isEqualTo(200);
		String resumedId = uuid();
		post("/track/collect", payload(APP_A, List.of(
			event(resumedId, disabledName, now + 2000, uuid(), sessionId, Map.of()))), null);
		awaitUntil("启用后同名事件恢复落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE event_id = ?", resumedId) == 1L);
	}

	/** dropped 指标指定 reason 计数（Micrometer 注册表直查；该 reason 尚无计数时按 0） */
	private double droppedCount(String reason) {
		Counter counter = meterRegistry.find(TrackConstants.METRIC_DROPPED).tags("reason", reason).counter();
		return counter == null ? 0.0 : counter.count();
	}

	// ---------- 测试工具 ----------

	/** 组装批量上报体（协议：app_key/schema_version/sdk/sent_at/events） */
	private Map<String, Object> payload(String appKey, List<Map<String, Object>> events) {
		Map<String, Object> root = new HashMap<>();
		root.put("app_key", appKey);
		root.put("schema_version", 1);
		root.put("sdk", Map.of("platform", "web", "version", "0.0.1-it"));
		root.put("sent_at", System.currentTimeMillis());
		root.put("events", events);
		return root;
	}

	private Map<String, Object> event(String eventId, String name, long ts, String distinctId, String sessionId,
									  Map<String, Object> props) {
		return event(eventId, name, ts, distinctId, sessionId, props, null);
	}

	private Map<String, Object> event(String eventId, String name, long ts, String distinctId, String sessionId,
									  Map<String, Object> props, Long userId) {
		Map<String, Object> e = new HashMap<>();
		e.put("event_id", eventId);
		e.put("event", name);
		e.put("ts", ts);
		e.put("distinct_id", distinctId);
		e.put("session_id", sessionId);
		e.put("props", props);
		if (userId != null) {
			e.put("user_id", userId);
		}
		return e;
	}

	/** $identify 事件（props.user_id = 声称绑定用户） */
	private Map<String, Object> identifyEvent(String eventId, String distinctId, String sessionId, Long claimedUserId) {
		return event(eventId, "$identify", System.currentTimeMillis(), distinctId, sessionId,
			Map.of("user_id", claimedUserId));
	}

	private long identityCount(String distinctId) {
		return trackLong("SELECT count(*) AS c FROM track_identity WHERE app_key = ? AND distinct_id = ?",
			APP_A, distinctId);
	}

	/** 业务库查用户 id（按用户名 + 平台租户：建租户类测试会造出各租户同名 admin，须按租户收窄） */
	private Long currentUserId(String username) {
		Row row = Db.selectOneBySql("SELECT id FROM sys_user WHERE username = ? AND tenant_id = ? AND is_deleted = 0",
			username, PLATFORM_TENANT);
		assertThat(row).as("用户应存在: " + username).isNotNull();
		return row.getLong("id");
	}

	/** 确保共享设备用例第二用户存在（幂等；无会话上下文经 ignore 落库），返回其 id */
	private Long ensureUserB() {
		return TenantContext.ignore(() -> {
			SysUser existing = userMapper.selectOneByQuery(QueryWrapper.create().eq("username", USER_B));
			if (existing != null) {
				return existing.getId();
			}
			SysUser user = new SysUser();
			user.setUsername(USER_B);
			user.setPassword(passwordEncoder.encode(ADMIN_PASSWORD));
			user.setNickname("共享设备用户B");
			user.setStatus(1);
			user.setTenantId(PLATFORM_TENANT);
			userMapper.insertSelective(user);
			return user.getId();
		});
	}

	private LocalDateTime toLdt(Object jdbcValue) {
		assertThat(jdbcValue).isInstanceOf(Timestamp.class);
		return ((Timestamp) jdbcValue).toLocalDateTime();
	}

	private String uuid() {
		return UUID.randomUUID().toString();
	}
}
