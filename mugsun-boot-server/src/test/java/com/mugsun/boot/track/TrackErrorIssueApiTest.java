package com.mugsun.boot.track;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.common.constant.TrackConstants;
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
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 错误问题单闭环：带 release 的 $error → 问题单出现 → 指派 → 解决 → 同指纹再报显示复发；
 * 列表可筛 release/status。安全硬拦：读 list / 写 edit 分码 403；跨租户/跨 appKey 不可读写。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TrackErrorIssueApiTest extends AbstractTrackIntegrationTest {

	private static final String APP = "it-err-issue";
	private static final String APP_OTHER = "it-err-issue-other";
	private static final String APP_B = "it-err-issue-b";
	private static final String FP = "it-err-issue-fp-1";
	private static final String RELEASE_A = "2.1.0";
	private static final String RELEASE_B = "2.2.0";
	private static final String LOW_USERNAME = "it_err_issue_low";
	private static final String LOW_PASSWORD = "123456";
	private static final String ROLE_CODE = "it-err-issue-noperm";
	private static final String LIST_ONLY_USERNAME = "it_err_issue_list";
	private static final String LIST_ONLY_ROLE = "it-err-issue-listonly";

	private String adminToken;
	private Long adminId;
	private String lowToken;
	private String listOnlyToken;
	private String tenantBCode;
	private String tenantBToken;
	private Long issueId;

	@BeforeAll
	void prepare() {
		adminToken = loginAdmin();
		adminId = currentUserId(ADMIN_USERNAME);
		lowToken = createLowPrivilegeUser();
		listOnlyToken = createListOnlyUser();

		Map<String, Object> tenantBody = new HashMap<>();
		tenantBody.put("tenantName", "错误问题单测试租户");
		tenantBody.put("contactUser", "错误问题单测");
		JsonNode tenantResp = readBody(post("/system/tenant/create", tenantBody, adminToken));
		assertThat(tenantResp.path("code").asInt()).as("建租户：" + tenantResp.path("msg").asText()).isEqualTo(200);
		tenantBCode = tenantCodeOf(tenantResp);
		normalizeTenantAdminPassword(tenantBCode);
		tenantBToken = login(tenantBCode, ADMIN_USERNAME, ADMIN_PASSWORD);

		seedApp(APP, PLATFORM_TENANT);
		seedApp(APP_OTHER, PLATFORM_TENANT);
		seedApp(APP_B, tenantBCode);
	}

	@AfterAll
	void cleanup() {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> {
			Db.updateBySql("DELETE FROM track_error_issue WHERE app_key IN (?, ?, ?)", APP, APP_OTHER, APP_B);
			Db.updateBySql("DELETE FROM track_event WHERE app_key IN (?, ?, ?)", APP, APP_OTHER, APP_B);
			Db.updateBySql("DELETE FROM track_session WHERE app_key IN (?, ?, ?)", APP, APP_OTHER, APP_B);
			Db.updateBySql("DELETE FROM track_event_def WHERE app_key IN (?, ?, ?)", APP, APP_OTHER, APP_B);
			Db.updateBySql("DELETE FROM track_app WHERE app_key IN (?, ?, ?)", APP, APP_OTHER, APP_B);
		});
	}

	@Test
	@Order(1)
	void ingestCreatesOpenIssue() {
		pushError(APP, FP, RELEASE_A, "boom-a", "/err-a");
		awaitUntil("问题单落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_error_issue WHERE app_key = ? AND fingerprint = ? AND is_deleted = 0",
			APP, FP) == 1L);

		JsonNode page = readBodyAssertOk(get("/system/track/error-issues/page?appKey=" + APP
			+ "&pageNum=1&pageSize=20", adminToken));
		assertThat(page.path("totalRow").asLong()).isEqualTo(1L);
		JsonNode row = page.path("records").get(0);
		assertThat(row.path("fingerprint").asText()).isEqualTo(FP);
		assertThat(row.path("status").asText()).isEqualTo(TrackConstants.ERROR_ISSUE_STATUS_OPEN);
		assertThat(row.path("firstRelease").asText()).isEqualTo(RELEASE_A);
		assertThat(row.path("lastRelease").asText()).isEqualTo(RELEASE_A);
		assertThat(row.path("eventCount").asLong()).isEqualTo(1L);
		assertThat(row.path("regressed").asBoolean()).isFalse();
		issueId = row.path("id").asLong();

		JsonNode byRelease = readBodyAssertOk(get("/system/track/error-issues/page?appKey=" + APP
			+ "&release=" + RELEASE_A + "&pageNum=1&pageSize=20", adminToken));
		assertThat(byRelease.path("totalRow").asLong()).isEqualTo(1L);
		assertThat(byRelease.path("records").get(0).path("releaseFlag").asText())
			.isEqualTo(TrackConstants.ERROR_ISSUE_FLAG_NEW);

		JsonNode miss = readBodyAssertOk(get("/system/track/error-issues/page?appKey=" + APP
			+ "&release=9.9.9&pageNum=1&pageSize=20", adminToken));
		assertThat(miss.path("totalRow").asLong()).isEqualTo(0L);
	}

	@Test
	@Order(2)
	void assignResolveAndRegress() {
		assertThat(issueId).as("依赖上一用例落库的问题单").isNotNull();

		JsonNode assigned = readBodyAssertOk(post("/system/track/error-issues/assign",
			Map.of("id", issueId, "appKey", APP, "assigneeId", adminId), adminToken));
		assertThat(assigned.path("status").asText()).isEqualTo(TrackConstants.ERROR_ISSUE_STATUS_ASSIGNED);
		assertThat(assigned.path("assigneeId").asLong()).isEqualTo(adminId);
		assertThat(assigned.path("assigneeName").asText()).isNotBlank();

		JsonNode statusPage = readBodyAssertOk(get("/system/track/error-issues/page?appKey=" + APP
			+ "&status=ASSIGNED&pageNum=1&pageSize=20", adminToken));
		assertThat(statusPage.path("totalRow").asLong()).isEqualTo(1L);

		JsonNode resolved = readBodyAssertOk(post("/system/track/error-issues/resolve",
			Map.of("id", issueId, "appKey", APP), adminToken));
		assertThat(resolved.path("status").asText()).isEqualTo(TrackConstants.ERROR_ISSUE_STATUS_RESOLVED);
		assertThat(resolved.path("resolvedAt").isNull()).isFalse();

		// 同指纹新 release 再报 → 复发
		pushError(APP, FP, RELEASE_B, "boom-b", "/err-b");
		awaitUntil("复发刷新", () -> {
			Row r = trackRow("SELECT status, regressed, last_release, resolved_at FROM track_error_issue"
				+ " WHERE id = ? AND is_deleted = 0", issueId);
			return r != null
				&& r.getInt("regressed") == 1
				&& TrackConstants.ERROR_ISSUE_STATUS_ASSIGNED.equals(r.getString("status"))
				&& RELEASE_B.equals(r.getString("last_release"))
				&& r.get("resolved_at") == null;
		});

		JsonNode detail = readBodyAssertOk(get("/system/track/error-issues/detail?id=" + issueId
			+ "&appKey=" + APP, adminToken));
		assertThat(detail.path("regressed").asBoolean()).isTrue();
		assertThat(detail.path("status").asText()).isEqualTo(TrackConstants.ERROR_ISSUE_STATUS_ASSIGNED);
		assertThat(detail.path("resolvedAt").isNull()).isTrue();
		assertThat(detail.path("lastRelease").asText()).isEqualTo(RELEASE_B);
		assertThat(detail.path("latestSessionId").asText()).isNotBlank();
		assertThat(detail.path("latestDistinctId").asText()).isNotBlank();
		assertThat(detail.path("eventCount").asLong()).isGreaterThanOrEqualTo(2L);

		JsonNode regPage = readBodyAssertOk(get("/system/track/error-issues/page?appKey=" + APP
			+ "&release=" + RELEASE_B + "&regressedOnly=true&pageNum=1&pageSize=20", adminToken));
		assertThat(regPage.path("totalRow").asLong()).isEqualTo(1L);
		assertThat(regPage.path("records").get(0).path("releaseFlag").asText())
			.isEqualTo(TrackConstants.ERROR_ISSUE_FLAG_REGRESSED);
	}

	@Test
	@Order(3)
	void authReopenAndIdor() {
		assertThat(issueId).as("依赖上一用例落库的问题单").isNotNull();

		// 401 / 零权 403（读 list / 写 edit）
		assertThat(get("/system/track/error-issues/page?appKey=" + APP, null).getStatusCode().value())
			.as("无 token 应 401").isEqualTo(401);
		assertThat(get("/system/track/error-issues/page?appKey=" + APP, lowToken).getStatusCode().value())
			.as("零权读应 403").isEqualTo(403);
		assertThat(post("/system/track/error-issues/resolve",
			Map.of("id", issueId, "appKey", APP), lowToken).getStatusCode().value())
			.as("零权写应 403").isEqualTo(403);

		// 仅有 list 无 edit → 可读不可写
		assertThat(readBody(get("/system/track/error-issues/page?appKey=" + APP
			+ "&pageNum=1&pageSize=5", listOnlyToken)).path("code").asInt())
			.as("仅 list 可读").isEqualTo(200);
		assertThat(post("/system/track/error-issues/resolve",
			Map.of("id", issueId, "appKey", APP), listOnlyToken).getStatusCode().value())
			.as("仅 list 写应 403").isEqualTo(403);
		assertThat(post("/system/track/error-issues/assign",
			Map.of("id", issueId, "appKey", APP, "assigneeId", adminId), listOnlyToken)
			.getStatusCode().value()).as("仅 list 指派应 403").isEqualTo(403);
		assertThat(post("/system/track/error-issues/reopen",
			Map.of("id", issueId, "appKey", APP), listOnlyToken).getStatusCode().value())
			.as("仅 list 重开应 403").isEqualTo(403);

		// 仅凭 id 不带 appKey → 业务失败（禁止 IDOR）
		JsonNode idOnly = readBody(get("/system/track/error-issues/detail?id=" + issueId, adminToken));
		assertThat(idOnly.path("code").asInt()).as("详情缺 appKey").isEqualTo(400);
		JsonNode resolveIdOnly = readBody(post("/system/track/error-issues/resolve",
			Map.of("id", issueId), adminToken));
		assertThat(resolveIdOnly.path("code").asInt()).as("解决缺 appKey").isEqualTo(400);

		// 同租户跨 appKey
		JsonNode crossAppDetail = readBody(get("/system/track/error-issues/detail?id=" + issueId
			+ "&appKey=" + APP_OTHER, adminToken));
		assertThat(crossAppDetail.path("code").asInt()).as("跨 app 详情").isEqualTo(400);
		assertThat(crossAppDetail.path("msg").asText()).contains("问题单不存在或无权访问");
		JsonNode crossAppWrite = readBody(post("/system/track/error-issues/resolve",
			Map.of("id", issueId, "appKey", APP_OTHER), adminToken));
		assertThat(crossAppWrite.path("code").asInt()).as("跨 app 解决").isEqualTo(400);
		Row stillAssigned = trackRow("SELECT status FROM track_error_issue WHERE id = ? AND is_deleted = 0", issueId);
		assertThat(stillAssigned.getString("status")).as("跨 app 写不应生效")
			.isEqualTo(TrackConstants.ERROR_ISSUE_STATUS_ASSIGNED);

		// 跨租户：B 管理员对 A 应用/问题单
		JsonNode crossTenantPage = readBody(get("/system/track/error-issues/page?appKey=" + APP
			+ "&pageNum=1&pageSize=5", tenantBToken));
		assertThat(crossTenantPage.path("code").asInt()).as("跨租户 page").isEqualTo(400);
		assertThat(crossTenantPage.path("msg").asText()).contains("应用不存在或无权访问");
		JsonNode crossTenantDetail = readBody(get("/system/track/error-issues/detail?id=" + issueId
			+ "&appKey=" + APP, tenantBToken));
		assertThat(crossTenantDetail.path("code").asInt()).as("跨租户详情").isEqualTo(400);
		JsonNode crossTenantWrite = readBody(post("/system/track/error-issues/resolve",
			Map.of("id", issueId, "appKey", APP), tenantBToken));
		assertThat(crossTenantWrite.path("code").asInt()).as("跨租户解决").isEqualTo(400);

		// 解决后再指派：reopen + 清 resolvedAt
		readBodyAssertOk(post("/system/track/error-issues/resolve",
			Map.of("id", issueId, "appKey", APP), adminToken));
		JsonNode reassigned = readBodyAssertOk(post("/system/track/error-issues/assign",
			Map.of("id", issueId, "appKey", APP, "assigneeId", adminId), adminToken));
		assertThat(reassigned.path("status").asText()).isEqualTo(TrackConstants.ERROR_ISSUE_STATUS_ASSIGNED);
		assertThat(reassigned.path("resolvedAt").isNull()).isTrue();

		Map<String, Object> cancelBody = new HashMap<>();
		cancelBody.put("id", issueId);
		cancelBody.put("appKey", APP);
		JsonNode cancelled = readBodyAssertOk(post("/system/track/error-issues/assign", cancelBody, adminToken));
		assertThat(cancelled.path("status").asText()).isEqualTo(TrackConstants.ERROR_ISSUE_STATUS_OPEN);
		assertThat(cancelled.path("assigneeId").isNull()).isTrue();

		readBodyAssertOk(post("/system/track/error-issues/resolve",
			Map.of("id", issueId, "appKey", APP), adminToken));
		JsonNode reopened = readBodyAssertOk(post("/system/track/error-issues/reopen",
			Map.of("id", issueId, "appKey", APP), adminToken));
		assertThat(reopened.path("status").asText()).isEqualTo(TrackConstants.ERROR_ISSUE_STATUS_OPEN);
		assertThat(reopened.path("resolvedAt").isNull()).isTrue();
	}

	private void pushError(String appKey, String fingerprint, String release, String message, String urlPath) {
		Map<String, Object> props = new HashMap<>();
		props.put("message", message);
		props.put("error_fingerprint", fingerprint);
		props.put("url_path", urlPath);
		props.put("release", release);
		props.put("stack", "Error: " + message + "\n at x.js:1:1");
		ResponseEntity<String> resp = post("/track/collect",
			payload(appKey, List.of(event(TrackConstants.EVENT_ERROR, uuid(), uuid(), props))), null);
		assertThat(readBody(resp).path("data").path("received").asInt()).as("$error 入队").isEqualTo(1);
	}

	private void seedApp(String appKey, String tenantId) {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(
			"INSERT INTO track_app (id, app_key, app_name, platform, tenant_id, sample_rate, enabled,"
				+ " create_time, update_time, is_deleted)"
				+ " VALUES (?, ?, ?, 'web', ?, 100, 1, now(), now(), 0)"
				+ " ON CONFLICT (app_key) WHERE is_deleted = 0 DO UPDATE SET tenant_id = EXCLUDED.tenant_id,"
				+ " enabled = 1",
			IdUtil.getSnowflakeNextId(), appKey, "错误问题单测-" + appKey, tenantId));
	}

	private JsonNode readBodyAssertOk(ResponseEntity<String> resp) {
		JsonNode json = readBody(resp);
		assertThat(json.path("code").asInt()).as("请求应成功：" + json).isEqualTo(200);
		return json.path("data");
	}

	private String createLowPrivilegeUser() {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "错误问题单零权限角色");
		role.put("roleCode", ROLE_CODE);
		role.put("sort", 99);
		role.put("dataScope", 1);
		JsonNode roleResp = readBody(post("/system/role/submit", role, adminToken));
		assertThat(roleResp.path("code").asInt()).as("建角色：" + roleResp.path("msg").asText()).isEqualTo(200);

		Map<String, Object> user = new HashMap<>();
		user.put("username", LOW_USERNAME);
		user.put("nickname", "错误问题单低权用户");
		user.put("password", LOW_PASSWORD);
		user.put("status", 1);
		JsonNode userResp = readBody(post("/system/user/submit", user, adminToken));
		assertThat(userResp.path("code").asInt()).as("建用户：" + userResp.path("msg").asText()).isEqualTo(200);

		long roleId = findIdByField("/system/role/page?pageNum=1&pageSize=100", "roleCode", ROLE_CODE);
		long userId = findIdByField("/system/user/page?pageNum=1&pageSize=100", "username", LOW_USERNAME);
		JsonNode grant = readBody(post("/system/user/grant",
			Map.of("userId", userId, "roleIds", List.of(roleId)), adminToken));
		assertThat(grant.path("code").asInt()).as("授权：" + grant.path("msg").asText()).isEqualTo(200);
		return login(PLATFORM_TENANT, LOW_USERNAME, LOW_PASSWORD);
	}

	/** 仅授予 sys:track-error:list，用于验证写码独立拦截 */
	private String createListOnlyUser() {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "错误问题单只读角色");
		role.put("roleCode", LIST_ONLY_ROLE);
		role.put("sort", 98);
		role.put("dataScope", 1);
		assertThat(readBody(post("/system/role/submit", role, adminToken)).path("code").asInt()).isEqualTo(200);
		long roleId = findIdByField("/system/role/page?pageNum=1&pageSize=100", "roleCode", LIST_ONLY_ROLE);

		Row menu = Db.selectOneBySql(
			"SELECT id FROM sys_menu WHERE permission = ? AND is_deleted = 0 LIMIT 1",
			TrackConstants.PERM_ERROR_LIST);
		assertThat(menu).as("list 权限菜单应已种子").isNotNull();
		JsonNode roleGrant = readBody(post("/system/role/grant",
			Map.of("roleId", roleId, "menuIds", List.of(menu.getLong("id"))), adminToken));
		assertThat(roleGrant.path("code").asInt()).as("授 list 菜单：" + roleGrant.path("msg").asText()).isEqualTo(200);

		Map<String, Object> user = new HashMap<>();
		user.put("username", LIST_ONLY_USERNAME);
		user.put("nickname", "错误问题单只读用户");
		user.put("password", LOW_PASSWORD);
		user.put("status", 1);
		assertThat(readBody(post("/system/user/submit", user, adminToken)).path("code").asInt()).isEqualTo(200);
		long userId = findIdByField("/system/user/page?pageNum=1&pageSize=100", "username", LIST_ONLY_USERNAME);
		assertThat(readBody(post("/system/user/grant",
			Map.of("userId", userId, "roleIds", List.of(roleId)), adminToken)).path("code").asInt()).isEqualTo(200);
		return login(PLATFORM_TENANT, LIST_ONLY_USERNAME, LOW_PASSWORD);
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

	private Long currentUserId(String username) {
		Row row = Db.selectOneBySql("SELECT id FROM sys_user WHERE username = ? AND tenant_id = ? AND is_deleted = 0",
			username, PLATFORM_TENANT);
		assertThat(row).as("用户应存在: " + username).isNotNull();
		return row.getLong("id");
	}

	private Map<String, Object> payload(String appKey, List<Map<String, Object>> events) {
		Map<String, Object> root = new HashMap<>();
		root.put("app_key", appKey);
		root.put("schema_version", 1);
		root.put("sdk", Map.of("platform", "web", "version", "0.0.1-it"));
		root.put("sent_at", System.currentTimeMillis());
		root.put("events", events);
		return root;
	}

	private Map<String, Object> event(String name, String distinctId, String sessionId, Map<String, Object> props) {
		Map<String, Object> e = new HashMap<>();
		e.put("event_id", UUID.randomUUID().toString());
		e.put("event", name);
		e.put("ts", System.currentTimeMillis());
		e.put("distinct_id", distinctId);
		e.put("session_id", sessionId);
		e.put("props", props);
		return e;
	}

	private String uuid() {
		return UUID.randomUUID().toString();
	}
}
