package com.mugsun.boot.track;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * G106 埋点地理：摄入坐标圆整/拒非法、属地省份归一聚合、热力点、鉴权与租户隔离。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrackGeoApiTest extends AbstractTrackIntegrationTest {

	private static final String APP_A = "ak_test_geo_01";
	private static final String APP_B = "ak_test_geo_b";
	private static final String APP_IN = "ak_test_geo_in";
	private static final String LOW_USERNAME = "it-g106-lowpriv";
	private static final String LOW_PASSWORD = "123456";
	private static final String ROLE_CODE = "it-g106-noperm";

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
		tenantBody.put("tenantName", "G106测试租户");
		tenantBody.put("contactUser", "G106测试");
		JsonNode tenantResp = readBody(post("/system/tenant/create", tenantBody, adminToken));
		assertThat(tenantResp.path("code").asInt()).as("建租户：" + tenantResp.path("msg").asText()).isEqualTo(200);
		tenantBCode = tenantResp.path("data").asText();
		tenantBToken = login(tenantBCode, ADMIN_USERNAME, ADMIN_PASSWORD);
		lowToken = createLowPrivilegeUser();

		seedApp(APP_A, PLATFORM_TENANT);
		seedApp(APP_B, tenantBCode);
		seedApp(APP_IN, PLATFORM_TENANT);

		long now = System.currentTimeMillis();
		List<TrackIngestEvent> events = new ArrayList<>();
		events.add(ev(APP_A, TrackConstants.EVENT_PAGEVIEW, now, "g106-bj-1",
			"中国|0|北京|北京市|联通", 116.397428, 39.90923));
		events.add(ev(APP_A, TrackConstants.EVENT_PAGEVIEW, now + 1, "g106-bj-2",
			"中国|0|北京|北京市|电信", 116.404, 39.915));
		events.add(ev(APP_A, "$click", now + 2, "g106-zj-1",
			"中国|0|浙江省|杭州市|移动", null, null));
		events.add(ev(APP_A, TrackConstants.EVENT_PAGEVIEW, now + 3, "g106-empty",
			null, null, null));
		events.add(ev(APP_A, TrackConstants.EVENT_PAGEVIEW, now + 4, "g106-lan",
			"内网IP|内网IP|内网IP|内网IP", null, null));
		events.add(evTenant(APP_B, TrackConstants.EVENT_PAGEVIEW, now + 5, "g106-b", tenantBCode,
			"中国|0|上海|上海市|联通", 121.47, 31.23));
		store.insertEvents(events);
	}

	@AfterAll
	void cleanup() {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> {
			Db.updateBySql("DELETE FROM track_event WHERE app_key IN (?, ?, ?)", APP_A, APP_B, APP_IN);
			Db.updateBySql("DELETE FROM track_event_def WHERE app_key IN (?, ?, ?)", APP_A, APP_B, APP_IN);
			Db.updateBySql("DELETE FROM track_app WHERE app_key IN (?, ?, ?)", APP_A, APP_B, APP_IN);
		});
	}

	@Test
	void regionBucketsAndHeatPoints() {
		JsonNode data = readBodyAssertOk(get("/system/track/geo?appKey=" + APP_A + "&days=7", adminToken));
		JsonNode regions = data.path("regions");
		assertThat(regions.size()).as("北京 / 浙江 / 未知 / 内网 四桶").isGreaterThanOrEqualTo(4);

		long beijingPv = 0;
		long zhejiangEvents = 0;
		long unknownPv = 0;
		long intranetPv = 0;
		for (JsonNode row : regions) {
			String name = row.path("region").asText();
			if ("北京".equals(name)) {
				beijingPv = row.path("pv").asLong();
				assertThat(row.path("uv").asLong()).as("北京两访客").isEqualTo(2L);
			} else if ("浙江省".equals(name)) {
				zhejiangEvents = row.path("eventCount").asLong();
				assertThat(row.path("pv").asLong()).as("$click 不计 PV").isEqualTo(0L);
			} else if (TrackConstants.GEO_REGION_UNKNOWN.equals(name)) {
				unknownPv = row.path("pv").asLong();
			} else if (TrackConstants.GEO_REGION_INTRANET.equals(name)) {
				intranetPv = row.path("pv").asLong();
			}
		}
		assertThat(beijingPv).isEqualTo(2L);
		assertThat(zhejiangEvents).isEqualTo(1L);
		assertThat(unknownPv).isEqualTo(1L);
		assertThat(intranetPv).isEqualTo(1L);

		assertThat(data.path("geoCount").asLong()).as("仅北京两条带坐标").isEqualTo(2L);
		JsonNode points = data.path("points");
		assertThat(points.size()).isEqualTo(2);
		assertThat(points.get(0).path("lon").asDouble()).isBetween(116.3, 116.5);
		assertThat(points.get(0).path("lat").asDouble()).isBetween(39.8, 40.0);
	}

	@Test
	void ingestRoundsAndRejectsInvalidCoords() {
		String okId = UUID.randomUUID().toString();
		String badId = UUID.randomUUID().toString();
		long now = System.currentTimeMillis();
		ResponseEntity<String> resp = post("/track/collect", payload(APP_IN, List.of(
			event(okId, TrackConstants.EVENT_PAGEVIEW, now, Map.of(
				TrackConstants.PROP_GEO_LON, 116.397428, TrackConstants.PROP_GEO_LAT, 39.90923)),
			event(badId, TrackConstants.EVENT_PAGEVIEW, now + 1, Map.of(
				TrackConstants.PROP_GEO_LON, 200, TrackConstants.PROP_GEO_LAT, 39.9)))), null);
		assertThat(readBody(resp).path("data").path("received").asInt()).isEqualTo(2);
		awaitUntil("合法坐标落列", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE event_id = ? AND geo_lon IS NOT NULL", okId) == 1L);
		Number lon = (Number) trackRow("SELECT geo_lon, geo_lat FROM track_event WHERE event_id = ?", okId)
			.get("geo_lon");
		assertThat(lon.doubleValue()).isEqualTo(116.3974);
		assertThat(trackLong("SELECT count(*) AS c FROM track_event WHERE event_id = ? AND geo_lon IS NULL", badId))
			.as("越界坐标不落列").isEqualTo(1L);
	}

	@Test
	void accessControlAndTenantIsolation() {
		String url = "/system/track/geo?appKey=" + APP_A + "&days=7";
		assertThat(get(url, null).getStatusCode().value()).isEqualTo(401);
		assertThat(get(url, lowToken).getStatusCode().value()).isEqualTo(403);
		assertThat(readBody(get(url, tenantBToken)).path("code").asInt()).as("跨租户 400").isEqualTo(400);

		JsonNode own = readBodyAssertOk(get("/system/track/geo?appKey=" + APP_B + "&days=7", tenantBToken));
		assertThat(own.path("geoCount").asLong()).isEqualTo(1L);
		assertThat(own.path("regions").toString()).contains("上海");
		assertThat(own.path("regions").toString()).doesNotContain("北京");
	}

	@Test
	void parseLonLatFromNestedAndAliases() throws Exception {
		assertThat(TrackGeo.parseLonLat(null)).isNull();
		ObjectMapper om = new ObjectMapper();
		double[] nested = TrackGeo.parseLonLat(om.readTree("{\"geo\":{\"lng\":116.397428,\"lat\":39.90923}}"));
		assertThat(nested).isNotNull();
		assertThat(nested[0]).isEqualTo(116.3974);
		assertThat(nested[1]).isEqualTo(39.9092);
		double[] alias = TrackGeo.parseLonLat(om.readTree("{\"$geo_lon\":120.15,\"$geo_lat\":30.28}"));
		assertThat(alias).isNotNull();
		assertThat(alias[0]).isEqualTo(120.15);
		assertThat(TrackGeo.regionLabel(null)).isEqualTo(TrackConstants.GEO_REGION_UNKNOWN);
		assertThat(TrackGeo.regionLabel("中国|0|0|杭州市|电信")).isEqualTo("杭州市");
		assertThat(TrackGeo.clamp(181d, 30d)).isNull();
		assertThat(TrackGeo.round(116.397428)).isEqualTo(116.3974);
	}

	@Test
	void configGeoEnabledToggle() {
		long id = trackLong("SELECT id AS c FROM track_app WHERE app_key = ?", APP_IN);
		assertThat(id).as("种子应用").isPositive();
		Map<String, Object> on = new HashMap<>();
		on.put("id", id);
		on.put("geoEnabled", 1);
		assertThat(readBody(post("/system/track/app/submit", on, adminToken)).path("code").asInt()).isEqualTo(200);
		assertThat(readBody(get("/track/config?app_key=" + APP_IN, null)).path("data").path("geoEnabled").asBoolean())
			.as("打开后配置下发").isTrue();
		Map<String, Object> off = new HashMap<>();
		off.put("id", id);
		off.put("geoEnabled", 0);
		assertThat(readBody(post("/system/track/app/submit", off, adminToken)).path("code").asInt()).isEqualTo(200);
		assertThat(readBody(get("/track/config?app_key=" + APP_IN, null)).path("data").path("geoEnabled").asBoolean())
			.as("关闭后配置下发").isFalse();
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
			IdUtil.getSnowflakeNextId(), appKey, "G106测试-" + appKey, tenantId));
	}

	private TrackIngestEvent ev(String appKey, String name, long ms, String distinctId,
								String ipRegion, Double lon, Double lat) {
		return evTenant(appKey, name, ms, distinctId, PLATFORM_TENANT, ipRegion, lon, lat);
	}

	private TrackIngestEvent evTenant(String appKey, String name, long ms, String distinctId, String tenantId,
									  String ipRegion, Double lon, Double lat) {
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
		e.setUrlPath("/g106");
		e.setRoutePath("/g106");
		e.setIpRegion(ipRegion);
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

	private Map<String, Object> payload(String appKey, List<Map<String, Object>> events) {
		Map<String, Object> root = new HashMap<>();
		root.put("app_key", appKey);
		root.put("schema_version", 1);
		root.put("sdk", Map.of("platform", "web", "version", "0.0.1-it"));
		root.put("sent_at", System.currentTimeMillis());
		root.put("events", events);
		return root;
	}

	private Map<String, Object> event(String eventId, String name, long ts, Map<String, Object> props) {
		Map<String, Object> e = new HashMap<>();
		e.put("event_id", eventId);
		e.put("event", name);
		e.put("ts", ts);
		e.put("distinct_id", UUID.randomUUID().toString());
		e.put("session_id", UUID.randomUUID().toString());
		e.put("props", props);
		return e;
	}

	private String createLowPrivilegeUser() {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "G106零权限角色");
		role.put("roleCode", ROLE_CODE);
		role.put("sort", 99);
		role.put("dataScope", 1);
		assertThat(readBody(post("/system/role/submit", role, adminToken)).path("code").asInt()).isEqualTo(200);

		Map<String, Object> user = new HashMap<>();
		user.put("username", LOW_USERNAME);
		user.put("nickname", "G106低权用户");
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
