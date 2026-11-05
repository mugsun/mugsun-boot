package com.mugsun.boot.track;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.common.module.TrackAskIntent;
import com.mybatisflex.core.datasource.DataSourceKey;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 埋点问数据 P0：入口鉴权、意图二次权限、租户隔离、规则出数、回放空态/denied、
 * unsupported、LLM 失败不造数。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TrackAskApiTest extends AbstractTrackIntegrationTest {

	private static final String APP = "it-track-ask";
	private static final String APP_B = "it-track-ask-b";
	private static final String LOW_USERNAME = "it_track_ask_low";
	private static final String LOW_PASSWORD = "123456";
	private static final String ROLE_CODE = "it-track-ask-noperm";
	private static final String ASK_ONLY_USER = "it_track_ask_only";
	private static final String ASK_ONLY_ROLE = "it-track-ask-only";
	private static final String ASK_NO_REPLAY_USER = "it_track_ask_nr";
	private static final String ASK_NO_REPLAY_ROLE = "it-track-ask-nr";

	@Autowired
	private TrackEventStore store;

	private String adminToken;
	private String lowToken;
	private String askOnlyToken;
	private String askNoReplayToken;
	private String tenantBCode;
	private String tenantBToken;

	@BeforeAll
	void prepare() {
		adminToken = loginAdmin();
		lowToken = createLowPrivilegeUser();
		askOnlyToken = createAskOnlyUser();
		askNoReplayToken = createAskNoReplayUser();

		Map<String, Object> tenantBody = new HashMap<>();
		tenantBody.put("tenantName", "问数据测试租户");
		tenantBody.put("contactUser", "问数据测");
		JsonNode tenantResp = readBody(post("/system/tenant/create", tenantBody, adminToken));
		assertThat(tenantResp.path("code").asInt()).as("建租户：" + tenantResp.path("msg").asText()).isEqualTo(200);
		tenantBCode = tenantCodeOf(tenantResp);
		normalizeTenantAdminPassword(tenantBCode);
		tenantBToken = login(tenantBCode, ADMIN_USERNAME, ADMIN_PASSWORD);

		seedApp(APP, PLATFORM_TENANT);
		seedApp(APP_B, tenantBCode);

		long now = System.currentTimeMillis();
		store.insertEvents(List.of(
			storeEvent(APP, TrackConstants.EVENT_PAGEVIEW, now - 1000, "d-ask-1", "s-ask-1", "/home"),
			storeEvent(APP, TrackConstants.EVENT_PAGEVIEW, now - 500, "d-ask-2", "s-ask-2", "/about"),
			storeEvent(APP, "$click", now - 200, "d-ask-1", "s-ask-1", "/home")
		));
	}

	@AfterAll
	void cleanup() {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> {
			Db.updateBySql("DELETE FROM track_replay WHERE app_key IN (?, ?)", APP, APP_B);
			Db.updateBySql("DELETE FROM track_event WHERE app_key IN (?, ?)", APP, APP_B);
			Db.updateBySql("DELETE FROM track_session WHERE app_key IN (?, ?)", APP, APP_B);
			Db.updateBySql("DELETE FROM track_app WHERE app_key IN (?, ?)", APP, APP_B);
		});
		TrackAskTestLlmGateway.MODE.set("off");
	}

	@Test
	@Order(1)
	void overviewQuestionReturnsMetrics() {
		TrackAskTestLlmGateway.MODE.set("off");
		JsonNode data = readBodyAssertOk(ask(adminToken, APP, "最近 7 天概览", 7));
		assertThat(data.path("intent").asText()).isEqualTo("overview");
		assertThat(data.path("source").asText()).isEqualTo("track-analysis");
		assertThat(data.path("resultType").asText()).isEqualTo("metrics");
		assertThat(data.path("metrics").path("pv").asLong()).isGreaterThanOrEqualTo(2L);
		assertThat(data.path("metrics").path("uv").asLong()).isGreaterThanOrEqualTo(2L);
		assertThat(data.path("summary").asText()).contains("PV");
		assertThat(data.path("params").path("days").asInt()).isEqualTo(7);
		assertThat(data.path("replayDenied").asBoolean()).isFalse();
	}

	@Test
	@Order(2)
	void unsupportedReturns200Guide() {
		JsonNode body = readBody(ask(adminToken, APP, "帮我写一首诗夸夸产品经理", null));
		assertThat(body.path("code").asInt()).isEqualTo(200);
		JsonNode data = body.path("data");
		assertThat(data.path("intent").asText()).isEqualTo(TrackAskIntent.UNSUPPORTED);
		assertThat(data.path("resultType").asText()).isEqualTo("unsupported");
		assertThat(data.path("summary").asText()).isNotBlank();
		assertThat(data.path("metrics").isNull()).isTrue();
		assertThat(data.path("series").isNull()).isTrue();
	}

	@Test
	@Order(3)
	void authEntryAndIntentSecondary() {
		assertThat(ask(null, APP, "最近 7 天概览", 7).getStatusCode().value()).as("无 token").isEqualTo(401);
		assertThat(ask(lowToken, APP, "最近 7 天概览", 7).getStatusCode().value()).as("零权入口").isEqualTo(403);

		// 仅有 ask 入口、无 overview → 意图二次权限 403
		assertThat(ask(askOnlyToken, APP, "最近 7 天概览", 7).getStatusCode().value())
			.as("缺 overview 子权").isEqualTo(403);
	}

	@Test
	@Order(4)
	void crossTenantAppKeyRejected() {
		JsonNode cross = readBody(ask(tenantBToken, APP, "最近 7 天概览", 7));
		assertThat(cross.path("code").asInt()).as("跨租户").isEqualTo(400);
		assertThat(cross.path("msg").asText()).contains("应用");
	}

	@Test
	@Order(5)
	void replayDeniedWhenNoReplayPerm() {
		JsonNode data = readBodyAssertOk(ask(askNoReplayToken, APP, "最近 7 天概览", 7));
		assertThat(data.path("intent").asText()).isEqualTo("overview");
		assertThat(data.path("replayDenied").asBoolean()).isTrue();
		assertThat(data.path("replays").isArray()).isTrue();
		assertThat(data.path("replays")).isEmpty();
		assertThat(data.path("replayEmpty").asBoolean()).isTrue();
		assertThat(data.path("metrics").path("pv").asLong()).isGreaterThanOrEqualTo(2L);
	}

	@Test
	@Order(6)
	void replaysPreferErrorSessions() {
		String okSid = "ask-replay-ok-" + UUID.randomUUID().toString().substring(0, 8);
		String errSid = "ask-replay-err-" + UUID.randomUUID().toString().substring(0, 8);
		seedReplay(APP, okSid, 0, "/ok");
		seedReplay(APP, errSid, 1, "/err");

		JsonNode data = readBodyAssertOk(ask(adminToken, APP, "最近 7 天概览", 7));
		assertThat(data.path("replayDenied").asBoolean()).isFalse();
		assertThat(data.path("replays").size()).isGreaterThanOrEqualTo(1);
		JsonNode first = data.path("replays").get(0);
		assertThat(first.path("sessionId").asText()).isEqualTo(errSid);
		assertThat(first.path("appKey").asText()).isEqualTo(APP);
		assertThat(first.has("startTime")).isTrue();
		assertThat(first.has("entryPath")).isTrue();
		assertThat(first.has("hasError")).isTrue();
		assertThat(first.has("durationMs")).isTrue();
		assertThat(first.has("blocks")).isFalse();
	}

	@Test
	@Order(7)
	void llmFailureDoesNotFabricateMetrics() {
		TrackAskTestLlmGateway.MODE.set("throw");
		try {
			JsonNode data = readBodyAssertOk(ask(adminToken, APP, "最近 7 天概览", 7));
			// 降级规则解析后仍有真数；禁止出现无 source 的假 metrics
			assertThat(data.path("source").asText()).isEqualTo("track-analysis");
			assertThat(data.path("metrics").path("pv").asLong()).isGreaterThanOrEqualTo(2L);
			boolean warned = false;
			for (JsonNode w : data.path("warnings")) {
				if (w.asText().contains("模型")) {
					warned = true;
				}
			}
			assertThat(warned).as("应提示模型失败已降级").isTrue();
		} finally {
			TrackAskTestLlmGateway.MODE.set("off");
		}

		TrackAskTestLlmGateway.MODE.set("unsupported");
		try {
			JsonNode data = readBodyAssertOk(ask(adminToken, APP, "最近 7 天概览", 7));
			assertThat(data.path("intent").asText()).isEqualTo("overview");
			assertThat(data.path("metrics").path("pv").isMissingNode()).isFalse();
		} finally {
			TrackAskTestLlmGateway.MODE.set("off");
		}
	}

	private ResponseEntity<String> ask(String token, String appKey, String question, Integer days) {
		Map<String, Object> body = new HashMap<>();
		body.put("appKey", appKey);
		body.put("question", question);
		if (days != null) {
			body.put("days", days);
		}
		return post("/system/track/ask", body, token);
	}

	private JsonNode readBodyAssertOk(ResponseEntity<String> resp) {
		JsonNode json = readBody(resp);
		assertThat(json.path("code").asInt()).as("请求应成功：" + json).isEqualTo(200);
		return json.path("data");
	}

	private void seedApp(String appKey, String tenantId) {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(
			"INSERT INTO track_app (id, app_key, app_name, platform, tenant_id, sample_rate, enabled,"
				+ " create_time, update_time, is_deleted)"
				+ " VALUES (?, ?, ?, 'web', ?, 100, 1, now(), now(), 0)"
				+ " ON CONFLICT (app_key) WHERE is_deleted = 0 DO UPDATE SET tenant_id = EXCLUDED.tenant_id,"
				+ " enabled = 1",
			IdUtil.getSnowflakeNextId(), appKey, "问数据测-" + appKey, tenantId));
	}

	private void seedReplay(String appKey, String sessionId, int hasError, String entryPath) {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> {
			Db.updateBySql("DELETE FROM track_replay WHERE session_id = ?", sessionId);
			Db.updateBySql(
				"INSERT INTO track_replay (id, session_id, app_key, tenant_id, distinct_id, start_time, duration_ms,"
					+ " page_count, rrweb_events, size_bytes, has_error, entry_path, last_seq, storage_key,"
					+ " create_time, update_time, is_deleted)"
					+ " VALUES (?, ?, ?, ?, ?, now(), 1200, 1, 0, 0, ?, ?, 0, ?, now(), now(), 0)",
				IdUtil.getSnowflakeNextId(), sessionId, appKey, PLATFORM_TENANT, "d-" + sessionId,
				hasError, entryPath, "replay/" + appKey + "/202611/" + sessionId + "/");
		});
	}

	private TrackIngestEvent storeEvent(String appKey, String event, long ts, String distinctId,
										String sessionId, String path) {
		TrackIngestEvent e = new TrackIngestEvent();
		e.setEventId(UUID.randomUUID().toString());
		e.setAppKey(appKey);
		e.setEventName(event);
		e.setClientTsMs(ts);
		e.setTsMs(ts);
		e.setReceivedAtMs(ts);
		e.setClockSkewed(0);
		e.setDistinctId(distinctId);
		e.setSessionId(sessionId);
		e.setTenantId(PLATFORM_TENANT);
		e.setUrlPath(path);
		e.setRoutePath(path);
		e.setDevice("desktop");
		e.setPropsJson("{}");
		return e;
	}

	private String createLowPrivilegeUser() {
		return createUserWithMenus(LOW_USERNAME, "问数据零权", ROLE_CODE, "问数据零权限角色", List.of());
	}

	/** 仅入口 ask，无分析子权 */
	private String createAskOnlyUser() {
		Row menu = Db.selectOneBySql(
			"SELECT id FROM sys_menu WHERE permission = ? AND is_deleted = 0 LIMIT 1",
			TrackConstants.PERM_ASK_LIST);
		assertThat(menu).as("ask 菜单种子").isNotNull();
		return createUserWithMenus(ASK_ONLY_USER, "问数据仅入口", ASK_ONLY_ROLE, "问数据仅入口角色",
			List.of(menu.getLong("id")));
	}

	/** ask + overview，无 replay */
	private String createAskNoReplayUser() {
		Row ask = Db.selectOneBySql(
			"SELECT id FROM sys_menu WHERE permission = ? AND is_deleted = 0 LIMIT 1",
			TrackConstants.PERM_ASK_LIST);
		Row ov = Db.selectOneBySql(
			"SELECT id FROM sys_menu WHERE permission = ? AND is_deleted = 0 LIMIT 1",
			TrackConstants.PERM_OVERVIEW_LIST);
		assertThat(ask).isNotNull();
		assertThat(ov).isNotNull();
		return createUserWithMenus(ASK_NO_REPLAY_USER, "问数据无回放", ASK_NO_REPLAY_ROLE, "问数据无回放角色",
			List.of(ask.getLong("id"), ov.getLong("id")));
	}

	private String createUserWithMenus(String username, String nickname, String roleCode, String roleName,
									   List<Long> menuIds) {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", roleName);
		role.put("roleCode", roleCode);
		role.put("sort", 99);
		role.put("dataScope", 1);
		assertThat(readBody(post("/system/role/submit", role, adminToken)).path("code").asInt()).isEqualTo(200);
		long roleId = findIdByField("/system/role/page?pageNum=1&pageSize=100", "roleCode", roleCode);
		if (!menuIds.isEmpty()) {
			assertThat(readBody(post("/system/role/grant",
				Map.of("roleId", roleId, "menuIds", menuIds), adminToken)).path("code").asInt()).isEqualTo(200);
		}
		Map<String, Object> user = new HashMap<>();
		user.put("username", username);
		user.put("nickname", nickname);
		user.put("password", LOW_PASSWORD);
		user.put("status", 1);
		assertThat(readBody(post("/system/user/submit", user, adminToken)).path("code").asInt()).isEqualTo(200);
		long userId = findIdByField("/system/user/page?pageNum=1&pageSize=100", "username", username);
		assertThat(readBody(post("/system/user/grant",
			Map.of("userId", userId, "roleIds", List.of(roleId)), adminToken)).path("code").asInt()).isEqualTo(200);
		return login(PLATFORM_TENANT, username, LOW_PASSWORD);
	}

	private long findIdByField(String pageUrl, String field, String value) {
		JsonNode page = readBody(get(pageUrl, adminToken));
		for (JsonNode record : page.path("data").path("records")) {
			if (value.equals(record.path(field).asText())) {
				return record.path("id").asLong();
			}
		}
		throw new IllegalStateException("分页中未找到 " + field + "=" + value);
	}
}
