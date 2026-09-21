package com.mugsun.boot.track;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.job.TrackStats5mJob;
import com.mugsun.boot.track.job.TrackStatsDayJob;
import com.mugsun.boot.track.job.TrackVitalsJob;
import com.mybatisflex.core.datasource.DataSourceKey;
import com.mybatisflex.core.row.Db;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 埋点分析/管理 API 集成测试（G99 B3）：overview/trend/pages/vitals/errors/events/realtime/online 结构与数值、
 * 应用 CRUD 全流程（app_key 服务端生成/防伪造/sanitize）、事件定义认领/停用、无 token 401、低权 403、租户隔离。
 * <p>数据准备：今日事件走 /track/collect 真实摄入（实时流/在线同步通道一并覆盖），昨日事件经
 * {@link TrackEventStore} 直灌（receivedAtMs 指定昨天，day 表只聚合闭合日）；
 * 随后手动触发三个 rollup 任务（开放窗口/当日由 5m 与 vitals 任务覆盖）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrackAnalysisApiTest extends AbstractTrackIntegrationTest {

	private static final String APP_A = "it-ana-a";
	private static final String APP_B = "it-ana-b";
	private static final String LOW_USERNAME = "it-track-lowpriv";
	private static final String LOW_PASSWORD = "123456";
	private static final String ROLE_CODE = "it-track-noperm";

	/** 今日会话/匿名 ID（online/sessionCount 断言依据） */
	private static final String S1 = UUID.randomUUID().toString();
	private static final String S2 = UUID.randomUUID().toString();
	private static final String D1 = UUID.randomUUID().toString();
	private static final String D2 = UUID.randomUUID().toString();

	@Autowired
	private TrackEventStore store;
	@Autowired
	private TrackStats5mJob stats5mJob;
	@Autowired
	private TrackStatsDayJob statsDayJob;
	@Autowired
	private TrackVitalsJob vitalsJob;

	private String adminToken;
	private String tenantBCode;
	private String tenantBToken;
	private String lowToken;
	private LocalDate yesterday;

	@BeforeAll
	void setup() throws Exception {
		adminToken = loginAdmin();
		yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);

		// 第二租户（自动初始化其 admin/123456 管理员，持租户内 * 通配）
		Map<String, Object> tenantBody = new HashMap<>();
		tenantBody.put("tenantName", "埋点测试租户");
		tenantBody.put("contactUser", "埋点测试");
		JsonNode tenantResp = readBody(post("/system/tenant/create", tenantBody, adminToken));
		assertThat(tenantResp.path("code").asInt()).as("建租户：" + tenantResp.path("msg").asText()).isEqualTo(200);
		tenantBCode = tenantResp.path("data").asText();
		tenantBToken = login(tenantBCode, ADMIN_USERNAME, ADMIN_PASSWORD);

		// 低权用户（零权限角色；真实登录）
		lowToken = createLowPrivilegeUser();

		// 测试应用：A=平台租户，B=第二租户
		seedApp(APP_A, PLATFORM_TENANT);
		seedApp(APP_B, tenantBCode);

		// 今日事件（真实摄入链路）：pv×3 / click / 自定义 / $error / $web_vitals×3
		List<Map<String, Object>> events = new ArrayList<>();
		events.add(event("$pageview", D1, S1, Map.of("url_path", "/home", "route_path", "/home",
			"page_title", "首页", "referrer_domain", "google.com", "device", "desktop")));
		events.add(event("$pageview", D1, S1, Map.of("url_path", "/about", "route_path", "/about", "device", "desktop")));
		events.add(event("$pageview", D2, S2, Map.of("url_path", "/home", "route_path", "/home",
			"referrer_domain", "bing.com", "device", "mobile")));
		events.add(event("$click", D1, S1, Map.of("url_path", "/home", "route_path", "/home", "device", "desktop")));
		events.add(event("signup_click", D2, S2, Map.of("plan", "pro")));
		events.add(event("$error", D1, S1, Map.of("message", "boom", "stack", "TypeError: boom\n at a (http://x/a.js:1:1)",
			"release", "1.0.0", "error_fingerprint", "fp-aaa", "breadcrumbs", List.of(Map.of("type", "click")))));
		events.add(event("$web_vitals", D1, S1, Map.of("metric", "lcp", "value", 800, "route_path", "/home", "url_path", "/home")));
		events.add(event("$web_vitals", D1, S1, Map.of("metric", "lcp", "value", 1200, "route_path", "/home", "url_path", "/home")));
		events.add(event("$web_vitals", D1, S1, Map.of("metric", "cls", "value", 30, "route_path", "/home", "url_path", "/home")));
		ResponseEntity<String> resp = post("/track/collect", payload(APP_A, events), null);
		assertThat(readBody(resp).path("data").path("received").asInt()).as("9 条事件全部入队").isEqualTo(9);
		// B 应用一条 PV（租户隔离可见性正向用例）
		post("/track/collect", payload(APP_B, List.of(event("$pageview", UUID.randomUUID().toString(),
			UUID.randomUUID().toString(), Map.of("url_path", "/b", "route_path", "/b", "device", "desktop")))), null);

		awaitUntil("A 应用 9 条今日事件落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE app_key = ?", APP_A) == 9L);

		// 昨日事件（直灌，received_at 指定昨天 12:00 UTC）：pv×2（/home、/about，不同匿名 ID）
		long base = yesterday.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
		store.insertEvents(List.of(
			storeEvent(APP_A, "$pageview", base, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "/home"),
			storeEvent(APP_A, "$pageview", base + 1000, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "/about")));

		// 手动触发三轮 rollup（day 聚合昨日闭合日；5m/vitals 覆盖当日开放窗口）。
		// 5m 首轮从昨日事件起深补扫（单轮 288 窗封顶≈1 天），再跑一轮追平——与断言解耦调度 tick 竞态
		stats5mJob.rollupNow();
		stats5mJob.rollupNow();
		statsDayJob.rollupNow();
		vitalsJob.rollupNow();
	}

	// ==================== 分析查询 ====================

	/** overview：卡片数值精确（今日直算 + 昨日 stats_day）；分布结构齐备 */
	@Test
	void overviewCardsAndDistributions() {
		ResponseEntity<String> resp = get("/system/track/overview?appKey=" + APP_A + "&days=7", adminToken);
		assertThat(resp.getStatusCode().value()).isEqualTo(200);
		JsonNode data = readBody(resp).path("data");
		JsonNode cards = data.path("cards");
		assertThat(cards.path("pv").asLong()).as("pv=今日3+昨日2").isEqualTo(5L);
		assertThat(cards.path("uv").asLong()).as("uv=今日2（d1,d2）+昨日2").isEqualTo(4L);
		assertThat(cards.path("sessionCount").asLong()).as("今日活跃会话 s1/s2（昨日未造会话）").isEqualTo(2L);
		assertThat(cards.path("eventCount").asLong()).as("今日9+昨日2").isEqualTo(11L);
		assertThat(cards.has("avgSessionDurationMs")).isTrue();
		assertThat(cards.has("bounceRate")).isTrue();

		JsonNode referrer = data.path("referrerDist");
		assertThat(referrer.isArray()).isTrue();
		assertThat(referrer.toString()).contains("google.com");
		assertThat(data.path("deviceDist").toString()).contains("desktop");
		assertThat(data.path("browserTop").isArray()).isTrue();
	}

	/** trend：days=1 走 5m 粒度（pv 合计=今日 3）；days=7 走 day 粒度（昨日行 pv=2/uv=2） */
	@Test
	void trendSeriesBothGranularities() {
		JsonNode fiveMin = readBody(get("/system/track/trend?appKey=" + APP_A + "&days=1", adminToken)).path("data");
		assertThat(fiveMin.isArray()).isTrue();
		assertThat(fiveMin.size()).as("今日应有 5m 桶").isGreaterThanOrEqualTo(1);
		long pvSum = 0;
		for (JsonNode point : fiveMin) {
			assertThat(point.has("time")).isTrue();
			pvSum += point.path("pv").asLong();
		}
		assertThat(pvSum).as("5m 序列 pv 合计=今日 3 次 PV").isEqualTo(3L);

		JsonNode day = readBody(get("/system/track/trend?appKey=" + APP_A + "&days=7&dimType=overview", adminToken)).path("data");
		JsonNode yesterdayRow = null;
		for (JsonNode point : day) {
			if (yesterday.toString().equals(point.path("date").asText())) {
				yesterdayRow = point;
			}
		}
		assertThat(yesterdayRow).as("日粒度序列应含昨日行").isNotNull();
		assertThat(yesterdayRow.path("pv").asLong()).isEqualTo(2L);
		assertThat(yesterdayRow.path("uv").asLong()).isEqualTo(2L);
		assertThat(yesterdayRow.has("bounceCount")).isTrue();
	}

	/** pages：Top 页面（PV/UV/平均停留）；/home pv=3（今日2+昨日1），uv=3 */
	@Test
	void pagesTopWithUvAndDuration() {
		JsonNode data = readBody(get("/system/track/pages?appKey=" + APP_A + "&days=7", adminToken)).path("data");
		assertThat(data.isArray()).isTrue();
		JsonNode home = null;
		JsonNode about = null;
		for (JsonNode row : data) {
			if ("/home".equals(row.path("pagePath").asText())) {
				home = row;
			}
			if ("/about".equals(row.path("pagePath").asText())) {
				about = row;
			}
		}
		assertThat(home).as("/home 应上榜").isNotNull();
		assertThat(home.path("pv").asLong()).isEqualTo(3L);
		assertThat(home.path("uv").asLong()).as("/home UV=今日d1/d2+昨日1").isEqualTo(3L);
		assertThat(home.has("avgDurationMs")).isTrue();
		assertThat(about).as("/about 应上榜").isNotNull();
		assertThat(about.path("pv").asLong()).isEqualTo(2L);
	}

	/** vitals：直方图插值分位（lcp count=2，p50<=p75<=p95；cls 千分制 1 条）；routePath 过滤生效 */
	@Test
	void vitalsPercentilesFromHistogram() {
		JsonNode data = readBody(get("/system/track/vitals?appKey=" + APP_A + "&days=1", adminToken)).path("data");
		JsonNode lcp = null;
		JsonNode cls = null;
		for (JsonNode row : data) {
			if ("lcp".equals(row.path("metric").asText())) {
				lcp = row;
			}
			if ("cls".equals(row.path("metric").asText())) {
				cls = row;
			}
		}
		assertThat(lcp).as("lcp 指标卡应存在").isNotNull();
		assertThat(lcp.path("count").asLong()).isEqualTo(2L);
		assertThat(lcp.path("p50").asLong()).isLessThanOrEqualTo(lcp.path("p75").asLong());
		assertThat(lcp.path("p75").asLong()).isLessThanOrEqualTo(lcp.path("p95").asLong());
		assertThat(lcp.path("p50").asLong()).as("lcp p50 落 500..1000 桶内插值").isBetween(500L, 1000L);
		assertThat(cls).as("cls 指标卡应存在").isNotNull();
		assertThat(cls.path("count").asLong()).isEqualTo(1L);

		JsonNode filtered = readBody(get("/system/track/vitals?appKey=" + APP_A + "&days=1&routePath=/home", adminToken))
			.path("data");
		assertThat(filtered.size()).as("按路由过滤仍有数据").isGreaterThanOrEqualTo(1);
		JsonNode none = readBody(get("/system/track/vitals?appKey=" + APP_A + "&days=1&routePath=/no-such", adminToken))
			.path("data");
		assertThat(none.size()).as("不存在的路由应空").isEqualTo(0);
	}

	/** errors：按指纹分组（次数/影响会话/消息）+ 组内明细（堆栈/release/面包屑 props） */
	@Test
	void errorsGroupAndDetail() {
		JsonNode page = readBody(get("/system/track/errors/page?appKey=" + APP_A + "&days=7", adminToken)).path("data");
		assertThat(page.path("totalRow").asLong()).as("一个错误指纹组").isEqualTo(1L);
		JsonNode group = page.path("records").get(0);
		assertThat(group.path("fingerprint").asText()).isEqualTo("fp-aaa");
		assertThat(group.path("eventCount").asLong()).isEqualTo(1L);
		assertThat(group.path("sessionCount").asLong()).isEqualTo(1L);
		assertThat(group.path("message").asText()).isEqualTo("boom");
		assertThat(group.path("firstTime").asLong()).isGreaterThan(0L);
		assertThat(group.path("lastTime").asLong()).isGreaterThan(0L);

		JsonNode detail = readBody(get(
			"/system/track/errors/detail?appKey=" + APP_A + "&fingerprint=fp-aaa", adminToken)).path("data");
		assertThat(detail.path("totalRow").asLong()).isEqualTo(1L);
		JsonNode row = detail.path("records").get(0);
		assertThat(row.path("stack").asText()).contains("TypeError: boom");
		assertThat(row.path("release").asText()).isEqualTo("1.0.0");
		assertThat(row.path("props").asText()).as("props 含面包屑").contains("breadcrumbs");
		assertThat(row.path("sessionId").asText()).isEqualTo(S1);
	}

	/** events/page：按事件名分组计数；eventName 过滤；realtime：Redis Stream 尾部含新事件（不等落库） */
	@Test
	void eventsPageAndRealtimeStream() {
		JsonNode page = readBody(get("/system/track/events/page?appKey=" + APP_A + "&days=7&pageSize=50", adminToken))
			.path("data");
		boolean hasCustom = false;
		for (JsonNode row : page.path("records")) {
			if ("signup_click".equals(row.path("eventName").asText())) {
				hasCustom = true;
				assertThat(row.path("eventCount").asLong()).isEqualTo(1L);
			}
		}
		assertThat(hasCustom).as("自定义事件应入分组").isTrue();

		JsonNode filtered = readBody(get(
			"/system/track/events/page?appKey=" + APP_A + "&eventName=signup_click&days=7", adminToken)).path("data");
		assertThat(filtered.path("totalRow").asLong()).as("按事件名过滤").isEqualTo(1L);

		// 实时流：对 B 应用发一个标记事件（不污染 A 的概览精确断言），XREVRANGE 尾部立即可见
		// （应用归属按会话租户校验：B 应用属第二租户，须用 B 租户 token 读其实时流）
		post("/track/collect", payload(APP_B, List.of(event("rt_marker", UUID.randomUUID().toString(),
			UUID.randomUUID().toString(), Map.of("url_path", "/rt")))), null);
		JsonNode realtime = readBody(get("/system/track/events/realtime?appKey=" + APP_B + "&limit=50", tenantBToken))
			.path("data");
		boolean seen = false;
		for (JsonNode item : realtime) {
			if ("rt_marker".equals(item.path("eventName").asText())) {
				seen = true;
				assertThat(item.path("ts").asLong()).isGreaterThan(0L);
			}
		}
		assertThat(seen).as("实时流应含标记事件（不等落库）").isTrue();
	}

	/** online：ZSET 5 分钟窗计数（A 应用两个会话成员） */
	@Test
	void onlineCount() {
		JsonNode data = readBody(get("/system/track/online?appKey=" + APP_A, adminToken)).path("data");
		assertThat(data.path("online").asLong()).as("s1/s2 在 5 分钟窗内").isGreaterThanOrEqualTo(2L);
		assertThat(data.path("windowSeconds").asLong()).isEqualTo(TrackConstants.ONLINE_WINDOW_MS / 1000L);
	}

	// ==================== 应用 / 事件定义管理 ====================

	/** 应用 CRUD 全流程：新增（app_key 服务端生成）→ 分页可见 → 编辑（防 appKey 伪造）→ 删除（配置下发即拒） */
	@Test
	void appCrudFullFlow() {
		Map<String, Object> create = new HashMap<>();
		create.put("appName", "接口测试应用");
		create.put("sampleRate", 80);
		create.put("remark", "集成测试");
		JsonNode created = readBody(post("/system/track/app/submit", create, adminToken));
		assertThat(created.path("code").asInt()).as("新增：" + created.path("msg").asText()).isEqualTo(200);
		String appKey = created.path("data").path("appKey").asText();
		long id = created.path("data").path("id").asLong();
		assertThat(appKey).as("app_key 服务端生成规则").startsWith(TrackConstants.APP_KEY_PREFIX);
		assertThat(created.path("data").path("tenantId").asText()).isEqualTo(PLATFORM_TENANT);
		assertThat(created.path("data").path("sampleRate").asInt()).isEqualTo(80);
		assertThat(created.path("data").path("geoEnabled").asInt()).as("G106 精确位置默认关").isEqualTo(0);

		JsonNode found = findAppInPage(adminToken, appKey);
		assertThat(found).as("分页应能查到新应用").isNotNull();

		// 编辑：伪造 appKey/tenantId 被忽略；名称/采样率生效
		Map<String, Object> edit = new HashMap<>();
		edit.put("id", id);
		edit.put("appName", "改名应用");
		edit.put("sampleRate", 50);
		edit.put("appKey", "hacked-key");
		edit.put("tenantId", "T99999");
		JsonNode edited = readBody(post("/system/track/app/submit", edit, adminToken));
		assertThat(edited.path("code").asInt()).as("编辑：" + edited.path("msg").asText()).isEqualTo(200);
		JsonNode afterEdit = findAppInPage(adminToken, appKey);
		assertThat(afterEdit.path("appName").asText()).isEqualTo("改名应用");
		assertThat(afterEdit.path("sampleRate").asInt()).isEqualTo(50);
		assertThat(afterEdit.path("appKey").asText()).as("appKey 不可改").isEqualTo(appKey);
		assertThat(afterEdit.path("tenantId").asText()).as("tenantId 不可伪造").isEqualTo(PLATFORM_TENANT);

		// sanitize：伪造审计字段（isDeleted=1）不生效——记录新增后分页仍可见
		Map<String, Object> forge = new HashMap<>();
		forge.put("appName", "伪造审计字段");
		forge.put("isDeleted", 1);
		forge.put("createTime", "2000-01-01T00:00:00");
		JsonNode forged = readBody(post("/system/track/app/submit", forge, adminToken));
		assertThat(forged.path("code").asInt()).isEqualTo(200);
		String forgedKey = forged.path("data").path("appKey").asText();
		assertThat(findAppInPage(adminToken, forgedKey)).as("伪造 isDeleted=1 不应生效").isNotNull();
		// 清理伪造应用
		post("/system/track/app/remove", Map.of("id", forged.path("data").path("id").asLong()), adminToken);

		// 删除：逻辑删除 + 缓存失效 → 配置下发立拒
		JsonNode removed = readBody(post("/system/track/app/remove", Map.of("id", id), adminToken));
		assertThat(removed.path("code").asInt()).isEqualTo(200);
		ResponseEntity<String> config = get("/track/config?app_key=" + appKey, null);
		assertThat(config.getStatusCode().value()).as("删除后配置下发应 400").isEqualTo(400);
	}

	/** 应用回放配置（G100）：submit 放开回放三字段（含校验：采样率 0..100、保留天数 1..30、开关 0/1） */
	@Test
	void appReplayConfigSubmitAndValidation() {
		// 新增携带回放配置（采样率 0 合法 = 仅 $error 会话强传）
		Map<String, Object> create = new HashMap<>();
		create.put("appName", "回放配置应用");
		create.put("replayEnabled", 1);
		create.put("replaySampleRate", 0);
		create.put("replayRetentionDays", 30);
		JsonNode created = readBody(post("/system/track/app/submit", create, adminToken));
		assertThat(created.path("code").asInt()).as("新增：" + created.path("msg").asText()).isEqualTo(200);
		long id = created.path("data").path("id").asLong();
		assertThat(created.path("data").path("replayEnabled").asInt()).isEqualTo(1);
		assertThat(created.path("data").path("replaySampleRate").asInt()).isEqualTo(0);
		assertThat(created.path("data").path("replayRetentionDays").asInt()).isEqualTo(30);

		// 编辑改值生效
		JsonNode edited = readBody(post("/system/track/app/submit",
			Map.of("id", id, "replaySampleRate", 100, "replayRetentionDays", 1), adminToken));
		assertThat(edited.path("code").asInt()).as("编辑：" + edited.path("msg").asText()).isEqualTo(200);
		assertThat(edited.path("data").path("replaySampleRate").asInt()).isEqualTo(100);
		assertThat(edited.path("data").path("replayRetentionDays").asInt()).isEqualTo(1);

		// 越界一律 400（ServiceException 经全局兜底转 R 信封 code=400）
		JsonNode rate101 = readBody(post("/system/track/app/submit", Map.of("id", id, "replaySampleRate", 101), adminToken));
		assertThat(rate101.path("code").asInt()).isEqualTo(400);
		assertThat(rate101.path("msg").asText()).contains("回放采样率须 0..100");
		assertThat(readBody(post("/system/track/app/submit", Map.of("id", id, "replaySampleRate", -1), adminToken))
			.path("code").asInt()).isEqualTo(400);
		JsonNode days31 = readBody(post("/system/track/app/submit", Map.of("id", id, "replayRetentionDays", 31), adminToken));
		assertThat(days31.path("code").asInt()).isEqualTo(400);
		assertThat(days31.path("msg").asText()).contains("回放保留天数须 1..30");
		assertThat(readBody(post("/system/track/app/submit", Map.of("id", id, "replayRetentionDays", 0), adminToken))
			.path("code").asInt()).isEqualTo(400);
		assertThat(readBody(post("/system/track/app/submit", Map.of("id", id, "replayEnabled", 2), adminToken))
			.path("code").asInt()).isEqualTo(400);

		// 清理（逻辑删除，缓存即时失效）
		post("/system/track/app/remove", Map.of("id", id), adminToken);
	}

	/** 应用告警配置（G101）：submit 放开 alertEnabled/alertThreshold（0/1、1..1000 校验；缺省 0 关 / 10 次） */
	@Test
	void appAlertConfigSubmitAndValidation() {
		// 新增携带告警配置
		Map<String, Object> create = new HashMap<>();
		create.put("appName", "告警配置应用");
		create.put("alertEnabled", 1);
		create.put("alertThreshold", 500);
		JsonNode created = readBody(post("/system/track/app/submit", create, adminToken));
		assertThat(created.path("code").asInt()).as("新增：" + created.path("msg").asText()).isEqualTo(200);
		long id = created.path("data").path("id").asLong();
		assertThat(created.path("data").path("alertEnabled").asInt()).isEqualTo(1);
		assertThat(created.path("data").path("alertThreshold").asInt()).isEqualTo(500);

		// 缺省：未传字段走默认（0 关 / 阈值 10）
		JsonNode def = readBody(post("/system/track/app/submit", Map.of("appName", "告警默认应用"), adminToken));
		assertThat(def.path("code").asInt()).isEqualTo(200);
		assertThat(def.path("data").path("alertEnabled").asInt()).as("缺省告警关闭").isEqualTo(0);
		assertThat(def.path("data").path("alertThreshold").asInt()).as("缺省阈值 10")
			.isEqualTo(TrackConstants.DEFAULT_ALERT_THRESHOLD);
		post("/system/track/app/remove", Map.of("id", def.path("data").path("id").asLong()), adminToken);

		// 编辑改值生效
		JsonNode edited = readBody(post("/system/track/app/submit",
			Map.of("id", id, "alertEnabled", 0, "alertThreshold", 1), adminToken));
		assertThat(edited.path("code").asInt()).as("编辑：" + edited.path("msg").asText()).isEqualTo(200);
		assertThat(edited.path("data").path("alertEnabled").asInt()).isEqualTo(0);
		assertThat(edited.path("data").path("alertThreshold").asInt()).isEqualTo(1);

		// 越界一律 400（ServiceException 经全局兜底转 R 信封 code=400）
		JsonNode badSwitch = readBody(post("/system/track/app/submit", Map.of("id", id, "alertEnabled", 2), adminToken));
		assertThat(badSwitch.path("code").asInt()).isEqualTo(400);
		assertThat(badSwitch.path("msg").asText()).contains("alertEnabled 仅支持 0/1");
		JsonNode th0 = readBody(post("/system/track/app/submit", Map.of("id", id, "alertThreshold", 0), adminToken));
		assertThat(th0.path("code").asInt()).isEqualTo(400);
		assertThat(th0.path("msg").asText()).contains("告警阈值须 1.." + TrackConstants.ALERT_THRESHOLD_MAX);
		assertThat(readBody(post("/system/track/app/submit", Map.of("id", id, "alertThreshold",
			TrackConstants.ALERT_THRESHOLD_MAX + 1), adminToken)).path("code").asInt()).isEqualTo(400);

		// 清理（逻辑删除，缓存即时失效）
		post("/system/track/app/remove", Map.of("id", id), adminToken);
	}

	/** 事件定义：自动注册 → 分页可见 → 认领（显示名/说明/负责人/停用） */
	@Test
	void eventDefClaimAndDisable() {
		awaitUntil("signup_click 自动注册", () -> trackLong(
			"SELECT count(*) AS c FROM track_event_def WHERE app_key = ? AND event_name = 'signup_click'"
				+ " AND is_deleted = 0", APP_A) == 1L);
		JsonNode page = readBody(get("/system/track/event-def/page?appKey=" + APP_A + "&pageSize=100", adminToken))
			.path("data");
		JsonNode def = null;
		for (JsonNode row : page.path("records")) {
			if ("signup_click".equals(row.path("eventName").asText())) {
				def = row;
			}
		}
		assertThat(def).as("事件定义分页应含 signup_click").isNotNull();
		long defId = def.path("id").asLong();

		Map<String, Object> claim = new HashMap<>();
		claim.put("id", defId);
		claim.put("displayName", "注册点击");
		claim.put("description", "注册按钮点击");
		claim.put("owner", "tester");
		claim.put("status", 0);
		JsonNode claimed = readBody(post("/system/track/event-def/submit", claim, adminToken));
		assertThat(claimed.path("code").asInt()).as("认领：" + claimed.path("msg").asText()).isEqualTo(200);

		JsonNode after = readBody(get("/system/track/event-def/page?appKey=" + APP_A
			+ "&eventName=signup_click", adminToken)).path("data");
		JsonNode row = after.path("records").get(0);
		assertThat(row.path("displayName").asText()).isEqualTo("注册点击");
		assertThat(row.path("status").asInt()).as("停用生效").isEqualTo(0);
		assertThat(row.path("owner").asText()).isEqualTo("tester");
	}

	// ==================== 鉴权与租户隔离 ====================

	/** 无 token → 401（@SaCheckLogin） */
	@Test
	void noTokenUnauthorized() {
		ResponseEntity<String> resp = get("/system/track/overview?appKey=" + APP_A, null);
		assertThat(resp.getStatusCode().value()).isEqualTo(401);
	}

	/** 低权用户（零权限角色）→ 读 403 / 写 403 */
	@Test
	void lowPrivilegeForbidden() {
		ResponseEntity<String> read = get("/system/track/overview?appKey=" + APP_A, lowToken);
		assertThat(read.getStatusCode().value()).as("低权读分析应 403").isEqualTo(403);

		ResponseEntity<String> write = post("/system/track/app/submit", Map.of("appName", "越权应用"), lowToken);
		assertThat(write.getStatusCode().value()).as("低权写应用应 403").isEqualTo(403);

		ResponseEntity<String> defWrite = post("/system/track/event-def/submit", Map.of("id", 1), lowToken);
		assertThat(defWrite.getStatusCode().value()).as("低权写事件定义应 403").isEqualTo(403);
	}

	/** 租户隔离：B 租户管理员查不到 A（平台租户）应用数据；自己应用正常 */
	@Test
	void tenantIsolation() {
		// 跨租户概览：200 但全零（stats/明细均按 tenant_id 过滤）
		JsonNode cross = readBody(get("/system/track/overview?appKey=" + APP_A + "&days=7", tenantBToken)).path("data");
		assertThat(cross.path("cards").path("pv").asLong()).as("跨租户不可见 A 应用 PV").isEqualTo(0L);
		assertThat(cross.path("cards").path("eventCount").asLong()).isEqualTo(0L);

		// 跨租户应用分页：不含 A 应用，且全部记录属本租户
		JsonNode page = readBody(get("/system/track/app/page?pageNum=1&pageSize=100", tenantBToken)).path("data");
		for (JsonNode row : page.path("records")) {
			assertThat(row.path("appKey").asText()).isNotEqualTo(APP_A);
			assertThat(row.path("tenantId").asText()).isEqualTo(tenantBCode);
		}

		// 本租户应用：正常可见（setup 灌了 1 条 PV）
		JsonNode own = readBody(get("/system/track/overview?appKey=" + APP_B + "&days=1", tenantBToken)).path("data");
		assertThat(own.path("cards").path("pv").asLong()).as("本租户应用 PV 可见").isGreaterThanOrEqualTo(1L);

		// 跨租户实时流：应用归属校验拦截（R 400）
		JsonNode realtime = readBody(get("/system/track/events/realtime?appKey=" + APP_A, tenantBToken));
		assertThat(realtime.path("code").asInt()).as("跨租户实时流应拒").isEqualTo(400);
	}

	// ---------- 测试工具 ----------

	private void seedApp(String appKey, String tenantId) {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(
			"INSERT INTO track_app (id, app_key, app_name, platform, tenant_id, sample_rate, enabled,"
				+ " create_time, update_time, is_deleted) VALUES (?, ?, ?, 'web', ?, 100, 1, now(), now(), 0)"
				+ " ON CONFLICT (app_key) WHERE is_deleted = 0 DO UPDATE SET tenant_id = EXCLUDED.tenant_id, enabled = 1",
			IdUtil.getSnowflakeNextId(), appKey, "分析测试-" + appKey, tenantId));
	}

	/** 建零权限角色 + 低权用户并授权（PermissionGuardApiTest 同款），返回其 token */
	private String createLowPrivilegeUser() {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "埋点测试零权限角色");
		role.put("roleCode", ROLE_CODE);
		role.put("sort", 99);
		role.put("dataScope", 1);
		JsonNode roleResp = readBody(post("/system/role/submit", role, adminToken));
		assertThat(roleResp.path("code").asInt()).as("建角色：" + roleResp.path("msg").asText()).isEqualTo(200);

		Map<String, Object> user = new HashMap<>();
		user.put("username", LOW_USERNAME);
		user.put("nickname", "埋点测试低权用户");
		user.put("password", LOW_PASSWORD);
		user.put("status", 1);
		JsonNode userResp = readBody(post("/system/user/submit", user, adminToken));
		assertThat(userResp.path("code").asInt()).as("建用户：" + userResp.path("msg").asText()).isEqualTo(200);

		long roleId = findIdByField("/system/role/page?pageNum=1&pageSize=100", "roleCode", ROLE_CODE);
		long userId = findIdByField("/system/user/page?pageNum=1&pageSize=100", "username", LOW_USERNAME);
		JsonNode grant = readBody(post("/system/user/grant", Map.of("userId", userId, "roleIds", List.of(roleId)), adminToken));
		assertThat(grant.path("code").asInt()).as("授权：" + grant.path("msg").asText()).isEqualTo(200);
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

	/** 应用分页按 appKey 查找（两页内应命中；找不到返回 null） */
	private JsonNode findAppInPage(String token, String appKey) {
		for (int pageNum = 1; pageNum <= 2; pageNum++) {
			JsonNode page = readBody(get("/system/track/app/page?pageNum=" + pageNum + "&pageSize=100", token)).path("data");
			for (JsonNode row : page.path("records")) {
				if (appKey.equals(row.path("appKey").asText())) {
					return row;
				}
			}
		}
		return null;
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

	/** 直灌事件（receivedAtMs 指定；昨日数据用） */
	private TrackIngestEvent storeEvent(String appKey, String name, long ms, String distinctId, String sessionId,
										String routePath) {
		TrackIngestEvent e = new TrackIngestEvent();
		e.setEventId(UUID.randomUUID().toString());
		e.setAppKey(appKey);
		e.setEventName(name);
		e.setClientTsMs(ms);
		e.setTsMs(ms);
		e.setReceivedAtMs(ms);
		e.setClockSkewed(0);
		e.setDistinctId(distinctId);
		e.setSessionId(sessionId);
		e.setTenantId(PLATFORM_TENANT);
		e.setUrlPath(routePath);
		e.setRoutePath(routePath);
		e.setDevice("desktop");
		e.setPropsJson("{}");
		return e;
	}
}
