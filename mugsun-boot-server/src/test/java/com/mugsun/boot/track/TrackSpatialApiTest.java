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
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0 空间行为分析：agg/points、zoom→p、bbox 400、硬顶 truncated、空态 200、权限码。
 * 旧 /geo 契约不受影响（本类不改其断言）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrackSpatialApiTest extends AbstractTrackIntegrationTest {

	private static final String APP = "ak_test_spatial_01";
	private static final String APP_EMPTY = "ak_test_spatial_empty";
	private static final String APP_B = "ak_test_spatial_b";
	private static final String APP_TRUNC = "ak_test_spatial_trunc";
	private static final String LOW_USERNAME = "it_spatial_lowpriv";
	private static final String LOW_PASSWORD = "123456";
	private static final String ROLE_CODE = "it-spatial-noperm";

	@Autowired
	private TrackEventStore store;

	private String adminToken;
	private String tenantBCode;
	private String tenantBToken;
	private String lowToken;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		Map<String, Object> tenantBody = new HashMap<>();
		tenantBody.put("tenantName", "空间行为测试租户");
		tenantBody.put("contactUser", "空间测试");
		JsonNode tenantResp = readBody(post("/system/tenant/create", tenantBody, adminToken));
		assertThat(tenantResp.path("code").asInt()).as("建租户：" + tenantResp.path("msg").asText()).isEqualTo(200);
		tenantBCode = tenantCodeOf(tenantResp);
		normalizeTenantAdminPassword(tenantBCode);
		tenantBToken = login(tenantBCode, ADMIN_USERNAME, ADMIN_PASSWORD);
		lowToken = createLowPrivilegeUser();

		seedApp(APP, PLATFORM_TENANT);
		seedApp(APP_EMPTY, PLATFORM_TENANT);
		seedApp(APP_B, tenantBCode);
		seedApp(APP_TRUNC, PLATFORM_TENANT);

		long now = System.currentTimeMillis();
		List<TrackIngestEvent> events = new ArrayList<>();
		events.add(ev(APP, TrackConstants.EVENT_PAGEVIEW, now, "sp-bj-1", 116.3974, 39.9092));
		events.add(ev(APP, TrackConstants.EVENT_PAGEVIEW, now + 1, "sp-bj-2", 116.4040, 39.9150));
		events.add(ev(APP, "$click", now + 2, "sp-sh-1", 121.4737, 31.2304));
		events.add(ev(APP, TrackConstants.EVENT_PAGEVIEW, now + 3, "sp-no-geo", null, null));
		events.add(evTenant(APP_B, TrackConstants.EVENT_PAGEVIEW, now + 4, "sp-b", tenantBCode, 121.48, 31.23));
		store.insertEvents(events);

		// 硬顶：points 超过 2000 → truncated
		List<TrackIngestEvent> many = new ArrayList<>(TrackConstants.GEO_POINTS_MAX + 5);
		for (int i = 0; i < TrackConstants.GEO_POINTS_MAX + 5; i++) {
			double lon = 110.0 + (i % 1000) * 0.001;
			double lat = 30.0 + (i / 1000) * 0.001;
			many.add(ev(APP_TRUNC, TrackConstants.EVENT_PAGEVIEW, now + 100 + i, "sp-t-" + i, lon, lat));
		}
		store.insertEvents(many);
	}

	@AfterAll
	void cleanup() {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> {
			Db.updateBySql("DELETE FROM track_event WHERE app_key IN (?, ?, ?, ?)",
				APP, APP_EMPTY, APP_B, APP_TRUNC);
			Db.updateBySql("DELETE FROM track_event_def WHERE app_key IN (?, ?, ?, ?)",
				APP, APP_EMPTY, APP_B, APP_TRUNC);
			Db.updateBySql("DELETE FROM track_app WHERE app_key IN (?, ?, ?, ?)",
				APP, APP_EMPTY, APP_B, APP_TRUNC);
		});
	}

	@Test
	void aggDefaultAndZoomPrecision() {
		JsonNode data = readBodyAssertOk(get("/system/track/spatial?appKey=" + APP + "&days=7", adminToken));
		assertThat(data.path("mode").asText()).isEqualTo("agg");
		assertThat(data.path("cells").isArray()).isTrue();
		assertThat(data.path("geoCount").asLong()).isEqualTo(3L);
		assertThat(data.path("truncated").asBoolean()).isFalse();
		assertThat(data.path("cells").size()).isGreaterThanOrEqualTo(2);
		JsonNode cell0 = data.path("cells").get(0);
		assertThat(cell0.has("lon")).isTrue();
		assertThat(cell0.has("lat")).isTrue();
		assertThat(cell0.path("weight").asLong()).isEqualTo(cell0.path("count").asLong());
		assertThat(data.has("points")).isFalse();

		JsonNode zoom1 = readBodyAssertOk(get(
			"/system/track/spatial?appKey=" + APP + "&days=7&zoom=4&mode=agg", adminToken));
		assertThat(zoom1.path("cells").size()).isLessThanOrEqualTo(data.path("cells").size());

		JsonNode gp = readBodyAssertOk(get(
			"/system/track/spatial?appKey=" + APP + "&days=7&zoom=20&gridPrecision=1&mode=agg", adminToken));
		assertThat(gp.path("mode").asText()).isEqualTo("agg");
	}

	@Test
	void pointsModeHasJumpIdsNoIpNoAcc() {
		JsonNode data = readBodyAssertOk(get(
			"/system/track/spatial?appKey=" + APP + "&days=7&mode=points", adminToken));
		assertThat(data.path("mode").asText()).isEqualTo("points");
		assertThat(data.path("points").size()).isEqualTo(3);
		assertThat(data.path("geoCount").asLong()).isEqualTo(3L);
		assertThat(data.path("truncated").asBoolean()).isFalse();
		JsonNode p0 = data.path("points").get(0);
		assertThat(p0.has("eventName")).isTrue();
		assertThat(p0.has("ts")).isTrue();
		assertThat(p0.has("eventId")).isTrue();
		assertThat(p0.has("sessionId")).isTrue();
		assertThat(p0.has("distinctId")).isTrue();
		assertThat(p0.path("converted").asBoolean()).isFalse();
		assertThat(p0.has("ip")).isFalse();
		assertThat(p0.has("geo_acc")).isFalse();
		assertThat(data.has("cells")).isFalse();
	}

	@Test
	void emptyStateAndConvertedOnlyWithoutFunnel() {
		JsonNode empty = readBodyAssertOk(get(
			"/system/track/spatial?appKey=" + APP_EMPTY + "&days=7&mode=agg", adminToken));
		assertThat(empty.path("cells").size()).isEqualTo(0);
		assertThat(empty.path("geoCount").asLong()).isEqualTo(0L);
		assertThat(empty.path("truncated").asBoolean()).isFalse();

		JsonNode only = readBodyAssertOk(get(
			"/system/track/spatial?appKey=" + APP + "&days=7&mode=points&convertedOnly=true&funnelId=1",
			adminToken));
		assertThat(only.path("points").size()).isEqualTo(0);
		assertThat(only.path("geoCount").asLong()).isEqualTo(0L);
	}

	@Test
	void daysBoundAlignedWithFunnel() {
		JsonNode ok = readBodyAssertOk(get("/system/track/spatial?appKey=" + APP + "&days=30", adminToken));
		assertThat(ok.path("mode").asText()).isEqualTo("agg");

		ResponseEntity<String> over = get("/system/track/spatial?appKey=" + APP + "&days=31", adminToken);
		assertThat(readBody(over).path("code").asInt()).as("days=31 应业务400").isEqualTo(400);
		assertThat(readBody(over).path("msg").asText()).contains("30");
	}

	@Test
	void bboxIllegalReturns400() {
		ResponseEntity<String> cross = get(
			"/system/track/spatial?appKey=" + APP + "&days=7&bbox=170,10,-170,20", adminToken);
		assertThat(readBody(cross).path("code").asInt()).as("跨180应业务400").isEqualTo(400);

		ResponseEntity<String> bad = get(
			"/system/track/spatial?appKey=" + APP + "&days=7&bbox=1,2,3", adminToken);
		assertThat(readBody(bad).path("code").asInt()).isEqualTo(400);
	}

	@Test
	void pointsHardCapTruncated() {
		JsonNode data = readBodyAssertOk(get(
			"/system/track/spatial?appKey=" + APP_TRUNC + "&days=7&mode=points", adminToken));
		assertThat(data.path("points").size()).isEqualTo(TrackConstants.GEO_POINTS_MAX);
		assertThat(data.path("geoCount").asLong()).isGreaterThan(TrackConstants.GEO_POINTS_MAX);
		assertThat(data.path("truncated").asBoolean()).isTrue();
	}

	@Test
	void accessControlAndTenantIsolation() {
		String url = "/system/track/spatial?appKey=" + APP + "&days=7";
		assertThat(get(url, null).getStatusCode().value()).isEqualTo(401);
		assertThat(get(url, lowToken).getStatusCode().value()).isEqualTo(403);
		assertThat(readBody(get(url, tenantBToken)).path("code").asInt()).as("跨租户400").isEqualTo(400);

		JsonNode own = readBodyAssertOk(get(
			"/system/track/spatial?appKey=" + APP_B + "&days=7&mode=points", tenantBToken));
		assertThat(own.path("geoCount").asLong()).isEqualTo(1L);
	}

	@Test
	void oldGeoEndpointUntouched() {
		JsonNode data = readBodyAssertOk(get("/system/track/geo?appKey=" + APP + "&days=7", adminToken));
		assertThat(data.has("regions")).isTrue();
		assertThat(data.has("points")).isTrue();
		assertThat(data.has("geoCount")).isTrue();
		assertThat(data.has("mode")).isFalse();
		assertThat(data.has("cells")).isFalse();
		assertThat(data.has("truncated")).isFalse();
	}

	private JsonNode readBodyAssertOk(ResponseEntity<String> resp) {
		JsonNode json = readBody(resp);
		assertThat(json.path("code").asInt()).as("应成功：" + json).isEqualTo(200);
		return json.path("data");
	}

	private void seedApp(String appKey, String tenantId) {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(
			"INSERT INTO track_app (id, app_key, app_name, platform, tenant_id, sample_rate, enabled,"
				+ " create_time, update_time, is_deleted) VALUES (?, ?, ?, 'web', ?, 100, 1, now(), now(), 0)"
				+ " ON CONFLICT (app_key) WHERE is_deleted = 0 DO UPDATE SET tenant_id = EXCLUDED.tenant_id, enabled = 1",
			IdUtil.getSnowflakeNextId(), appKey, "空间测试-" + appKey, tenantId));
	}

	private TrackIngestEvent ev(String appKey, String name, long ms, String distinctId, Double lon, Double lat) {
		return evTenant(appKey, name, ms, distinctId, PLATFORM_TENANT, lon, lat);
	}

	private TrackIngestEvent evTenant(String appKey, String name, long ms, String distinctId, String tenantId,
									  Double lon, Double lat) {
		TrackIngestEvent e = new TrackIngestEvent();
		e.setEventId(UUID.randomUUID().toString());
		e.setAppKey(appKey);
		e.setEventName(name);
		e.setClientTsMs(ms);
		e.setTsMs(ms);
		e.setReceivedAtMs(ms);
		e.setClockSkewed(0);
		e.setDistinctId(distinctId);
		e.setSessionId(UUID.randomUUID().toString());
		e.setTenantId(tenantId);
		e.setUrlPath("/spatial");
		e.setRoutePath("/spatial");
		if (lon != null && lat != null) {
			double[] ll = TrackGeo.clamp(lon, lat);
			if (ll != null) {
				e.setGeoLon(ll[0]);
				e.setGeoLat(ll[1]);
			}
		}
		e.setPropsJson("{}");
		return e;
	}

	private String createLowPrivilegeUser() {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "空间零权限角色");
		role.put("roleCode", ROLE_CODE);
		role.put("sort", 99);
		role.put("dataScope", 1);
		assertThat(readBody(post("/system/role/submit", role, adminToken)).path("code").asInt()).isEqualTo(200);

		Map<String, Object> user = new HashMap<>();
		user.put("username", LOW_USERNAME);
		user.put("nickname", "空间低权用户");
		user.put("password", LOW_PASSWORD);
		user.put("status", 1);
		assertThat(readBody(post("/system/user/submit", user, adminToken)).path("code").asInt()).isEqualTo(200);

		long roleId = findIdByField("/system/role/page?pageNum=1&pageSize=100", "roleCode", ROLE_CODE);
		long userId = findIdByField("/system/user/page?pageNum=1&pageSize=100", "username", LOW_USERNAME);
		assertThat(readBody(post("/system/user/grant", Map.of("userId", userId, "roleIds", List.of(roleId)), adminToken))
			.path("code").asInt()).isEqualTo(200);
		return login(PLATFORM_TENANT, LOW_USERNAME, LOW_PASSWORD);
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
