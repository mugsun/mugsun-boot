package com.mugsun.boot.track;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mybatisflex.core.datasource.DataSourceKey;
import com.mybatisflex.core.row.Db;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接入自检：最近收到时间 + 拒收原因（含修复建议）记录与读取；租户隔离；只为存在的应用记录。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrackIntegrationHealthApiTest extends AbstractTrackIntegrationTest {

	private static final String APP_H = "it_health_a";
	private static final String APP_DIS = "it_health_dis";
	private static final String APP_HB = "it_health_b";
	private static final String APP_GHOST = "it_health_ghost";

	@Autowired
	private StringRedisTemplate redis;

	private String adminToken;
	private String tenantBToken;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		Map<String, Object> tenantBody = new HashMap<>();
		tenantBody.put("tenantName", "自检测试租户");
		tenantBody.put("contactUser", "自检");
		JsonNode tenantResp = readBody(post("/system/tenant/create", tenantBody, adminToken));
		assertThat(tenantResp.path("code").asInt()).as("建租户：" + tenantResp.path("msg").asText()).isEqualTo(200);
		String tenantB = tenantResp.path("data").asText();
		tenantBToken = login(tenantB, ADMIN_USERNAME, ADMIN_PASSWORD);
		seedApp(APP_H, PLATFORM_TENANT, 1);
		seedApp(APP_DIS, PLATFORM_TENANT, 0);
		seedApp(APP_HB, tenantB, 1);
		clearRedis();
	}

	@AfterAll
	void cleanup() {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> {
			for (String k : List.of(APP_H, APP_DIS, APP_HB)) {
				Db.updateBySql("DELETE FROM track_event WHERE app_key = ?", k);
				Db.updateBySql("DELETE FROM track_event_def WHERE app_key = ?", k);
				Db.updateBySql("DELETE FROM track_app WHERE app_key = ?", k);
			}
		});
		clearRedis();
	}

	/** ① 正常事件刷新最近收到时间；事件级问题按原因计数并带修复建议 */
	@Test
	void recordsReceivedAndEventLevelReasons() {
		long before = System.currentTimeMillis();
		List<Map<String, Object>> events = new ArrayList<>();
		events.add(event("$pageview", System.currentTimeMillis()));
		events.add(event("1bad_name", System.currentTimeMillis()));
		events.add(event("ok_event", 1000L));                                       // 1970 年：离谱时间
		events.add(event("skewed_event", System.currentTimeMillis() - 2L * 86400000)); // 偏差 2 天：校时收下
		Map<String, Object> missing = event("ok_event", System.currentTimeMillis());
		missing.remove("distinct_id");
		events.add(missing);
		assertThat(collect(APP_H, events).getStatusCode().value()).isEqualTo(200);

		JsonNode data = readBodyAssertOk(get("/system/track/app/health?appKey=" + APP_H, adminToken));
		assertThat(data.path("lastReceivedAt").asLong()).isGreaterThanOrEqualTo(before);
		assertThat(data.path("lastReceivedSource").asText()).isEqualTo("redis");
		Map<String, JsonNode> byCode = byCode(data.path("reasons"));
		assertThat(byCode).containsKeys("BAD_EVENT_NAME", "TS_ABSURD", "CLOCK_SKEWED", "SCHEMA_ERROR");
		assertThat(byCode.get("BAD_EVENT_NAME").path("today").asLong()).isGreaterThanOrEqualTo(1);
		assertThat(byCode.get("BAD_EVENT_NAME").path("severity").asText()).isEqualTo("fail");
		assertThat(byCode.get("CLOCK_SKEWED").path("severity").asText()).isEqualTo("warn");
		for (JsonNode r : data.path("reasons")) {
			assertThat(r.path("suggestion").asText()).as(r.path("code").asText() + " 须带修复建议").isNotBlank();
			assertThat(r.path("last7d").asLong()).isGreaterThanOrEqualTo(r.path("today").asLong());
		}
		// fail 原因排在 warn 前
		List<String> severities = new ArrayList<>();
		data.path("reasons").forEach(r -> severities.add(r.path("severity").asText()));
		assertThat(severities.indexOf("warn")).isGreaterThan(severities.lastIndexOf("fail"));
	}

	/** ② 请求级拒收：坏请求体（靠 query app_key 归属）、限流以外的停用应用；样本只含服务端提示 */
	@Test
	void recordsRequestLevelRejections() {
		ResponseEntity<String> bad = post("/track/collect?app_key=" + APP_H, "not-an-object", null);
		assertThat(bad.getStatusCode().value()).isEqualTo(400);
		ResponseEntity<String> dis = collect(APP_DIS, List.of(event("$pageview", System.currentTimeMillis())));
		assertThat(dis.getStatusCode().value()).isEqualTo(400);

		Map<String, JsonNode> h = byCode(readBodyAssertOk(get("/system/track/app/health?appKey=" + APP_H, adminToken)).path("reasons"));
		assertThat(h).containsKey("BAD_PAYLOAD");
		assertThat(h.get("BAD_PAYLOAD").path("lastAt").asLong()).isPositive();
		assertThat(h.get("BAD_PAYLOAD").path("lastMessage").asText()).contains("JSON").doesNotContain("not-an-object");

		JsonNode d = readBodyAssertOk(get("/system/track/app/health?appKey=" + APP_DIS, adminToken));
		assertThat(d.path("enabled").asBoolean()).isFalse();
		assertThat(byCode(d.path("reasons"))).containsKey("APP_DISABLED");
	}

	/** ③ 不存在的 appKey 不在 Redis 造键（防随便填的 appKey 刷爆 Redis） */
	@Test
	void unknownAppKeyNotRecorded() {
		assertThat(collect(APP_GHOST, List.of(event("$pageview", System.currentTimeMillis()))).getStatusCode().value()).isEqualTo(400);
		post("/track/collect?app_key=" + APP_GHOST, "x", null);
		Set<String> keys = redis.keys(TrackIntegrationHealth.REJ_PREFIX + APP_GHOST + "*");
		assertThat(keys).isEmpty();
		assertThat(redis.hasKey(TrackIntegrationHealth.LAST_PREFIX + APP_GHOST)).isFalse();
	}

	/** ④ 租户隔离：别的租户看不到也查不到本租户应用的自检 */
	@Test
	void tenantIsolation() {
		collect(APP_HB, List.of(event("1bad", System.currentTimeMillis())));
		JsonNode cross = readBody(get("/system/track/app/health?appKey=" + APP_H, tenantBToken));
		assertThat(cross.path("code").asInt()).isNotEqualTo(200);

		JsonNode summaryB = readBodyAssertOk(get("/system/track/app/health/summary", tenantBToken));
		List<String> keysB = new ArrayList<>();
		summaryB.forEach(n -> keysB.add(n.path("appKey").asText()));
		assertThat(keysB).contains(APP_HB).doesNotContain(APP_H, APP_DIS);

		JsonNode own = readBodyAssertOk(get("/system/track/app/health?appKey=" + APP_HB, tenantBToken));
		assertThat(byCode(own.path("reasons"))).containsKey("BAD_EVENT_NAME");

		JsonNode summaryA = readBodyAssertOk(get("/system/track/app/health/summary", adminToken));
		JsonNode rowA = null;
		for (JsonNode n : summaryA) {
			if (APP_H.equals(n.path("appKey").asText())) {
				rowA = n;
			}
		}
		assertThat(rowA).isNotNull();
		assertThat(rowA.has("reasons")).as("摘要不带建议正文").isFalse();
		assertThat(rowA.path("staleAfterMs").asLong()).isEqualTo(TrackIntegrationHealth.STALE_AFTER_MS);
	}

	/** ⑤ 未登录 401 */
	@Test
	void requiresLogin() {
		assertThat(readBody(get("/system/track/app/health?appKey=" + APP_H, null)).path("code").asInt()).isEqualTo(401);
	}

	// ---------- 工具 ----------

	private Map<String, JsonNode> byCode(JsonNode reasons) {
		Map<String, JsonNode> m = new HashMap<>();
		reasons.forEach(r -> m.put(r.path("code").asText(), r));
		return m;
	}

	private void seedApp(String appKey, String tenantId, int enabled) {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(
			"INSERT INTO track_app (id, app_key, app_name, platform, tenant_id, sample_rate, enabled, create_time, update_time, is_deleted)"
				+ " VALUES (?, ?, ?, 'web', ?, 100, ?, now(), now(), 0)"
				+ " ON CONFLICT (app_key) WHERE is_deleted = 0 DO UPDATE SET tenant_id = EXCLUDED.tenant_id, enabled = EXCLUDED.enabled",
			IdUtil.getSnowflakeNextId(), appKey, "自检测试-" + appKey, tenantId, enabled));
	}

	private ResponseEntity<String> collect(String appKey, List<Map<String, Object>> events) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("app_key", appKey);
		payload.put("schema_version", 1);
		payload.put("sdk", Map.of("platform", "web", "version", "0.0.1-it"));
		payload.put("sent_at", System.currentTimeMillis());
		payload.put("events", events);
		return post("/track/collect", payload, null);
	}

	private Map<String, Object> event(String name, long ts) {
		Map<String, Object> e = new HashMap<>();
		e.put("event_id", uuid());
		e.put("event", name);
		e.put("ts", ts);
		e.put("distinct_id", uuid());
		e.put("session_id", uuid());
		e.put("props", Map.of("url_path", "/health"));
		return e;
	}

	private JsonNode readBodyAssertOk(ResponseEntity<String> resp) {
		JsonNode json = readBody(resp);
		assertThat(json.path("code").asInt()).as("请求应成功：" + json).isEqualTo(200);
		return json.path("data");
	}

	private String uuid() {
		return java.util.UUID.randomUUID().toString();
	}

	private void clearRedis() {
		for (String k : List.of(APP_H, APP_DIS, APP_HB, APP_GHOST)) {
			Set<String> keys = redis.keys(TrackConstants.REDIS_PREFIX + "health:*" + k + "*");
			if (keys != null && !keys.isEmpty()) {
				redis.delete(keys);
			}
		}
	}
}
