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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入口页、退出页（{@code GET /system/track/pages?type=entry|exit}）的集成测试。
 *
 * <p>测试数据是平台租户一个应用下的 9 次会话，页面按先后排成（括号里是特意加进去的干扰）：
 * <pre>
 * s1  /home → /list →（一条没有页面地址的浏览）→ /detail → /cart
 * s2  /home →（一个自定义事件，地址 /zzz）→ /list → /detail（只有原始路径，没有路由模板）
 * s3  /home → /list → /home → /list → /pay
 * s4  /home → /home（刷新）→ /about
 * s5  /list → /home → /list → /detail
 * s6  /home
 * s7  /search → /list
 * s8  /list
 * s9  （只有一条没有页面地址的浏览和一个自定义事件）
 * </pre>
 * 按这组数据算出来：
 * <pre>
 * 入口页  /home 5 次会话，跳出 1（s6）；/list 2 次，跳出 1（s8）；/search 1 次，跳出 0
 * 退出页  /detail 2 次会话，被浏览 3 次；/list 2 次，被浏览 8 次；
 *         /about、/cart、/pay 各 1 次，各被浏览 1 次；/home 1 次，被浏览 8 次
 * </pre>
 * 逐项核对：
 * <ul>
 *   <li>入口页、退出页的会话数、跳出数、浏览次数和两个比率；两边的会话数加起来都是 8（s9 没有页面，不算）；</li>
 *   <li>没有页面地址的浏览、不是页面浏览的事件不算（s1、s2、s9）；没有路由模板时用原始路径（s2）；</li>
 *   <li>刷新算两次浏览，不算跳出（s4）；只看了一页的会话，入口页也是它的退出页（s6、s8）；</li>
 *   <li>条数上限、统计范围外的数据不算、不带类型时和以前一样返回访问最多的页面；</li>
 *   <li>入参不对返回 400 并说明怎么改，英文请求返回英文提示；</li>
 *   <li>没登录 401、没有权限 403、各租户只看得到自己的数据。</li>
 * </ul>
 * 事件都放在两天前（相对现在计算，不写死日期）：近 7 天看得到，只看今天看不到。
 * 用到的应用标识是本测试专用的，测完按应用标识删掉事件和应用。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrackEntryExitPagesApiTest extends AbstractTrackIntegrationTest {

	private static final String APP = "ak_test_edge_pages_01";
	private static final String APP_B = "ak_test_edge_pages_b";
	private static final String LOW_USERNAME = "it_edge_pages_lowpriv";
	private static final String LOW_PASSWORD = NEW_USER_PASSWORD;
	private static final String ROLE_CODE = "it-edge-pages-noperm";

	private static final String HOME = "/home";
	private static final String LIST = "/list";
	private static final String DETAIL = "/detail";

	private static final long MIN_MS = 60000L;
	private static final long DAY_MS = 86400000L;

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
		tenantBody.put("tenantName", "入口退出页测试租户");
		tenantBody.put("contactUser", "入口退出页测试");
		JsonNode tenantResp = readBody(post("/system/tenant/create", tenantBody, adminToken));
		assertThat(tenantResp.path("code").asInt()).as("建租户：" + tenantResp.path("msg").asText()).isEqualTo(200);
		tenantBCode = tenantCodeOf(tenantResp);
		normalizeTenantAdminPassword(tenantBCode);
		tenantBToken = login(tenantBCode, ADMIN_USERNAME, ADMIN_PASSWORD);
		lowToken = createLowPrivilegeUser();

		seedApp(APP, PLATFORM_TENANT);
		seedApp(APP_B, tenantBCode);

		long t0 = System.currentTimeMillis() - 2L * DAY_MS;
		List<TrackIngestEvent> events = new ArrayList<>();
		// s1：中间那条没有页面地址的浏览不算，这次会话是 4 次页面浏览
		events.add(view("it-edge-s1", t0, HOME));
		events.add(view("it-edge-s1", t0 + MIN_MS, LIST));
		events.add(event(APP, PLATFORM_TENANT, "it-edge-s1", TrackConstants.EVENT_PAGEVIEW, t0 + 2 * MIN_MS, "", ""));
		events.add(view("it-edge-s1", t0 + 3 * MIN_MS, DETAIL));
		events.add(view("it-edge-s1", t0 + 4 * MIN_MS, "/cart"));
		// s2：自定义事件不是页面浏览；最后一页没有路由模板，用原始路径当页面，它是退出页
		events.add(view("it-edge-s2", t0, HOME));
		events.add(event(APP, PLATFORM_TENANT, "it-edge-s2", "add_cart", t0 + MIN_MS, "/zzz", "/zzz"));
		events.add(view("it-edge-s2", t0 + 2 * MIN_MS, LIST));
		events.add(event(APP, PLATFORM_TENANT, "it-edge-s2", TrackConstants.EVENT_PAGEVIEW, t0 + 3 * MIN_MS, "", DETAIL));
		// s3：/home、/list 各看了两次，退出页是 /pay
		events.add(view("it-edge-s3", t0, HOME));
		events.add(view("it-edge-s3", t0 + MIN_MS, LIST));
		events.add(view("it-edge-s3", t0 + 2 * MIN_MS, HOME));
		events.add(view("it-edge-s3", t0 + 3 * MIN_MS, LIST));
		events.add(view("it-edge-s3", t0 + 4 * MIN_MS, "/pay"));
		// s4：连着两次 /home（刷新）算两次浏览，不算跳出
		events.add(view("it-edge-s4", t0, HOME));
		events.add(view("it-edge-s4", t0 + MIN_MS, HOME));
		events.add(view("it-edge-s4", t0 + 2 * MIN_MS, "/about"));
		// s5：入口页是 /list
		events.add(view("it-edge-s5", t0, LIST));
		events.add(view("it-edge-s5", t0 + MIN_MS, HOME));
		events.add(view("it-edge-s5", t0 + 2 * MIN_MS, LIST));
		events.add(view("it-edge-s5", t0 + 3 * MIN_MS, DETAIL));
		// s6、s8：只看了一页，算跳出；入口页也是它的退出页
		events.add(view("it-edge-s6", t0, HOME));
		events.add(view("it-edge-s8", t0, LIST));
		// s7：两页
		events.add(view("it-edge-s7", t0, "/search"));
		events.add(view("it-edge-s7", t0 + MIN_MS, LIST));
		// s9：没有任何带地址的页面浏览，整次会话不进统计
		events.add(event(APP, PLATFORM_TENANT, "it-edge-s9", TrackConstants.EVENT_PAGEVIEW, t0, "", ""));
		events.add(event(APP, PLATFORM_TENANT, "it-edge-s9", "add_cart", t0 + MIN_MS, "/zzz", "/zzz"));
		store.insertEvents(events);

		// 另一个租户的应用：一次会话，页面地址和平台应用有重合（/home），两边的数不能串
		store.insertEvents(List.of(
			event(APP_B, tenantBCode, "it-edge-b1", TrackConstants.EVENT_PAGEVIEW, t0, HOME, HOME),
			event(APP_B, tenantBCode, "it-edge-b1", TrackConstants.EVENT_PAGEVIEW, t0 + MIN_MS, "/b-only", "/b-only")));

		assertThat(trackLong("SELECT count(*) AS c FROM track_event WHERE app_key = ?", APP))
			.as("平台应用的 27 条测试事件都写进去了").isEqualTo(27L);
		assertThat(trackLong("SELECT count(*) AS c FROM track_event WHERE app_key = ?", APP_B))
			.as("另一个租户的 2 条测试事件都写进去了").isEqualTo(2L);
	}

	@AfterAll
	void cleanup() {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> {
			Db.updateBySql("DELETE FROM track_event WHERE app_key IN (?, ?)", APP, APP_B);
			Db.updateBySql("DELETE FROM track_app WHERE app_key IN (?, ?)", APP, APP_B);
		});
	}

	// ==================== 入口页 ====================

	/** 按从这个页面进来的会话数排；跳出是只看了一页的会话；各项加起来是有页面浏览的 8 次会话 */
	@Test
	void entryPagesCountSessionsByTheirFirstPage() {
		JsonNode rows = ok(pages(adminToken, APP, "&type=entry&days=7"));
		assertThat(entryRows(rows)).containsExactly(
			"/home sessions=5 bounces=1 bounceRate=0.2",
			"/list sessions=2 bounces=1 bounceRate=0.5",
			"/search sessions=1 bounces=0 bounceRate=0.0");
		assertThat(sum(rows, "sessions")).as("s9 没有带地址的页面浏览，不算").isEqualTo(8L);
		assertThat(rows.get(0).has("pv")).as("入口页不返回浏览次数").isFalse();

		JsonNode byDefaultDays = ok(pages(adminToken, APP, "&type=entry"));
		assertThat(entryRows(byDefaultDays)).as("不带天数时是近 7 天").isEqualTo(entryRows(rows));
	}

	// ==================== 退出页 ====================

	/** 按在这个页面结束的会话数排，一样多的按页面地址排；退出率的分母是这个页面被浏览的次数 */
	@Test
	void exitPagesCountSessionsByTheirLastPage() {
		JsonNode rows = ok(pages(adminToken, APP, "&type=exit&days=7"));
		assertThat(exitRows(rows)).containsExactly(
			"/detail sessions=2 pv=3 exitRate=0.6667",
			"/list sessions=2 pv=8 exitRate=0.25",
			"/about sessions=1 pv=1 exitRate=1.0",
			"/cart sessions=1 pv=1 exitRate=1.0",
			"/home sessions=1 pv=8 exitRate=0.125",
			"/pay sessions=1 pv=1 exitRate=1.0");
		assertThat(sum(rows, "sessions")).as("每次会话只有一个退出页，加起来和入口页一样是 8").isEqualTo(8L);
		assertThat(rows.get(0).has("bounces")).as("退出页不返回跳出数").isFalse();
	}

	// ==================== 条数、统计范围、默认类型 ====================

	/** limit 只留前几条；事件在两天前，只看今天是空的；不带类型和带 top 一样，返回访问最多的页面 */
	@Test
	void limitWindowAndDefaultType() {
		assertThat(entryRows(ok(pages(adminToken, APP, "&type=entry&days=7&limit=2")))).containsExactly(
			"/home sessions=5 bounces=1 bounceRate=0.2",
			"/list sessions=2 bounces=1 bounceRate=0.5");
		assertThat(exitRows(ok(pages(adminToken, APP, "&type=exit&days=7&limit=1")))).containsExactly(
			"/detail sessions=2 pv=3 exitRate=0.6667");

		assertThat(ok(pages(adminToken, APP, "&type=entry&days=1")).size()).as("只看今天：两天前的会话不算").isZero();
		assertThat(ok(pages(adminToken, APP, "&type=exit&days=1")).size()).isZero();

		JsonNode plain = ok(pages(adminToken, APP, "&days=7"));
		JsonNode top = ok(pages(adminToken, APP, "&days=7&type=top"));
		assertThat(top).as("带 type=top 和不带类型是同一个结果").isEqualTo(plain);
		assertThat(plain.get(0).has("pv")).isTrue();
		assertThat(plain.get(0).has("uv")).isTrue();
		assertThat(plain.get(0).has("sessions")).as("访问最多的页面还是原来的字段").isFalse();
	}

	// ==================== 入参校验 ====================

	/** 入参不对：返回 400，提示里说明哪里不对、怎么改 */
	@Test
	void invalidParamsAreRejected() {
		assertRejected(pages(adminToken, APP, "&type=first"), "页面排行的类型不对");
		assertRejected(get("/system/track/pages?type=entry", adminToken), "请先选择应用");
		assertRejected(pages(adminToken, "ak_test_edge_pages_none", "&type=exit"), "这个应用不存在");
		assertRejected(pages(adminToken, APP, "&type=entry&days=0"), "统计天数只支持 1 到 30 天");
		assertRejected(pages(adminToken, APP, "&type=exit&days=" + (TrackConstants.PAGES_EDGE_DAYS_MAX + 1)),
			"统计天数只支持 1 到 30 天");

		JsonNode maxDays = ok(pages(adminToken, APP, "&type=entry&days=" + TrackConstants.PAGES_EDGE_DAYS_MAX));
		assertThat(sum(maxDays, "sessions")).as("最大天数可以查").isEqualTo(8L);
		JsonNode topBeyond = ok(pages(adminToken, APP, "&days=" + (TrackConstants.PAGES_EDGE_DAYS_MAX + 1)));
		assertThat(topBeyond.size()).as("访问最多的页面不受 30 天的限制").isPositive();
	}

	/** 英文请求：提示语是英文 */
	@Test
	void messagesFollowTheRequestLanguage() {
		HttpHeaders english = authHeaders(adminToken);
		english.set(HttpHeaders.ACCEPT_LANGUAGE, "en");
		JsonNode wrongType = readBody(rest.exchange("/system/track/pages?appKey={appKey}&type=first", HttpMethod.GET,
			new HttpEntity<>(english), String.class, APP));
		assertThat(wrongType.path("code").asInt()).isEqualTo(400);
		assertThat(wrongType.path("msg").asText()).isEqualTo(
			"The page ranking type is not valid. Please choose again from most visited, entry pages or exit pages.");
	}

	// ==================== 权限和租户隔离 ====================

	/** 没登录 401；没有权限 403；别的租户的应用查出来是空的；各租户只看得到自己的数据 */
	@Test
	void accessControlAndTenantIsolation() {
		assertThat(pages(null, APP, "&type=entry&days=7").getStatusCode().value())
			.as("没登录应该是 401").isEqualTo(401);
		assertThat(pages(lowToken, APP, "&type=entry&days=7").getStatusCode().value())
			.as("没有 " + TrackConstants.PERM_OVERVIEW_LIST + " 权限应该是 403").isEqualTo(403);

		assertThat(ok(pages(tenantBToken, APP, "&type=entry&days=7")).size())
			.as("别的租户查平台的应用：和访问最多的页面一样，查出来是空的").isZero();
		assertThat(ok(pages(tenantBToken, APP, "&type=exit&days=7")).size()).isZero();

		assertThat(entryRows(ok(pages(tenantBToken, APP_B, "&type=entry&days=7"))))
			.as("只算本租户的 1 次会话，平台应用的不串进来")
			.containsExactly("/home sessions=1 bounces=0 bounceRate=0.0");
		assertThat(exitRows(ok(pages(tenantBToken, APP_B, "&type=exit&days=7"))))
			.containsExactly("/b-only sessions=1 pv=1 exitRate=1.0");
	}

	// ---------- 测试工具 ----------

	/**
	 * 发一次查询。
	 *
	 * @param extra 追加在后面的查询串，如 {@code &type=entry&days=7}
	 */
	private ResponseEntity<String> pages(String token, String appKey, String extra) {
		return rest.exchange("/system/track/pages?appKey={appKey}" + extra, HttpMethod.GET,
			new HttpEntity<>(authHeaders(token)), String.class, appKey);
	}

	/** 把入口页的每一行写成一句，方便按顺序整个比对 */
	private static List<String> entryRows(JsonNode array) {
		List<String> list = new ArrayList<>();
		for (JsonNode item : array) {
			list.add(item.path("pagePath").asText() + " sessions=" + item.path("sessions").asLong()
				+ " bounces=" + item.path("bounces").asLong() + " bounceRate=" + item.path("bounceRate").asDouble());
		}
		return list;
	}

	/** 把退出页的每一行写成一句 */
	private static List<String> exitRows(JsonNode array) {
		List<String> list = new ArrayList<>();
		for (JsonNode item : array) {
			list.add(item.path("pagePath").asText() + " sessions=" + item.path("sessions").asLong()
				+ " pv=" + item.path("pv").asLong() + " exitRate=" + item.path("exitRate").asDouble());
		}
		return list;
	}

	private static long sum(JsonNode array, String field) {
		long total = 0L;
		for (JsonNode item : array) {
			total += item.path(field).asLong();
		}
		return total;
	}

	private JsonNode ok(ResponseEntity<String> resp) {
		JsonNode json = readBody(resp);
		assertThat(json.path("code").asInt()).as("请求应该成功：" + json).isEqualTo(200);
		return json.path("data");
	}

	private void assertRejected(ResponseEntity<String> resp, String messagePart) {
		JsonNode json = readBody(resp);
		assertThat(json.path("code").asInt()).as("应该返回 400：" + json).isEqualTo(400);
		assertThat(json.path("msg").asText()).contains(messagePart);
	}

	private void seedApp(String appKey, String tenantId) {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(
			"INSERT INTO track_app (id, app_key, app_name, platform, tenant_id, sample_rate, enabled,"
				+ " create_time, update_time, is_deleted) VALUES (?, ?, ?, 'web', ?, 100, 1, now(), now(), 0)"
				+ " ON CONFLICT (app_key) WHERE is_deleted = 0 DO UPDATE SET tenant_id = EXCLUDED.tenant_id, enabled = 1",
			IdUtil.getSnowflakeNextId(), appKey, "入口退出页测试-" + appKey, tenantId));
	}

	/** 平台应用的一次页面浏览，路由模板和原始路径都是这个地址 */
	private TrackIngestEvent view(String sessionId, long ms, String page) {
		return event(APP, PLATFORM_TENANT, sessionId, TrackConstants.EVENT_PAGEVIEW, ms, page, page);
	}

	private TrackIngestEvent event(String appKey, String tenantId, String sessionId, String name, long ms,
								   String routePath, String urlPath) {
		TrackIngestEvent e = new TrackIngestEvent();
		e.setEventId(UUID.randomUUID().toString());
		e.setAppKey(appKey);
		e.setEventName(name);
		e.setClientTsMs(ms);
		e.setTsMs(ms);
		e.setReceivedAtMs(ms);
		e.setClockSkewed(0);
		e.setDistinctId("d-" + sessionId);
		e.setSessionId(sessionId);
		e.setTenantId(tenantId);
		e.setUrlPath(urlPath);
		e.setRoutePath(routePath);
		e.setDevice("desktop");
		e.setPropsJson("{}");
		return e;
	}

	/** 建一个没有任何权限的角色和用户，返回这个用户的 token */
	private String createLowPrivilegeUser() {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "入口退出页零权限角色");
		role.put("roleCode", ROLE_CODE);
		role.put("sort", 99);
		role.put("dataScope", 1);
		JsonNode roleResp = readBody(post("/system/role/submit", role, adminToken));
		assertThat(roleResp.path("code").asInt()).as("建角色：" + roleResp.path("msg").asText()).isEqualTo(200);

		Map<String, Object> user = new HashMap<>();
		user.put("username", LOW_USERNAME);
		user.put("nickname", "入口退出页低权用户");
		user.put("password", LOW_PASSWORD);
		user.put("status", 1);
		JsonNode userResp = readBody(post("/system/user/submit", user, adminToken));
		assertThat(userResp.path("code").asInt()).as("建用户：" + userResp.path("msg").asText()).isEqualTo(200);

		long roleId = findIdByField("/system/role/page?pageNum=1&pageSize=100", "roleCode", ROLE_CODE);
		long userId = findIdByField("/system/user/page?pageNum=1&pageSize=100", "username", LOW_USERNAME);
		JsonNode grant = readBody(post("/system/user/grant", Map.of("userId", userId, "roleIds", List.of(roleId)), adminToken));
		assertThat(grant.path("code").asInt()).as("授权：" + grant.path("msg").asText()).isEqualTo(200);
		return loginNewUser(PLATFORM_TENANT, LOW_USERNAME, LOW_PASSWORD);
	}

	private long findIdByField(String pageUrl, String field, String value) {
		JsonNode page = readBody(get(pageUrl, adminToken));
		for (JsonNode record : page.path("data").path("records")) {
			if (value.equals(record.path(field).asText())) {
				return record.path("id").asLong();
			}
		}
		throw new IllegalStateException("分页里没有找到 " + field + "=" + value);
	}
}
