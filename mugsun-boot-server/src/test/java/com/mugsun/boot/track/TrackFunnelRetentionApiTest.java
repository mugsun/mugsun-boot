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

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G103 漏斗分析 + 留存分析 API 集成测试（§20 口径全量兑现）。
 * <p>漏斗：三步有序转化精确值（乱序不算 / 超转化窗口不算 / 中间穿插事件不影响——有序非紧邻）、
 * identity 归并（匿名期+登录期绑同一用户只计一次）、转化窗口三档（1h/24h/168h）分档断言、
 * steps 个数（去重后 &lt;2 / &gt;5）与非法事件名 400、windowHours 白名单外 400。
 * <p>留存：3 个 cohort 日 × 已知回访矩阵逐格精确值、回看窗首日截断排除（宁漏不假新客）、
 * identity 归并去重、days 钳制（100→30 / 缺省 7）。
 * <p>公共：401（无 token）/403（低权）/跨租户 400（assertAppVisible）/租户隔离正向（B 租户只见本租户数据）。
 * <p>数据准备：{@link TrackEventStore} 直灌固定 receivedAtMs（全部相对 now/UTC 今日计算，不硬编码绝对日期）；
 * 专用测试 appKey（ak_test_funnel_01 / ak_test_retention_01 / ak_test_funnel_b，互不染指）；
 * 测后 {@link AfterAll} 物理删本类 appKey 的事件/identity/应用行。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrackFunnelRetentionApiTest extends AbstractTrackIntegrationTest {

	private static final String APP_FUNNEL = "ak_test_funnel_01";
	private static final String APP_RETENTION = "ak_test_retention_01";
	private static final String APP_B = "ak_test_funnel_b";
	private static final String LOW_USERNAME = "it-g103-lowpriv";
	private static final String LOW_PASSWORD = "123456";
	private static final String ROLE_CODE = "it-g103-noperm";

	/** 漏斗三步事件名（CUSTOM_EVENT_NAME 合法自定义事件） */
	private static final String STEP1 = "signup_view";
	private static final String STEP2 = "signup_submit";
	private static final String STEP3 = "signup_done";
	private static final String STEPS_CSV = STEP1 + "," + STEP2 + "," + STEP3;

	/** identity 归并绑定的用户 ID（track_identity.user_id 为纯 bigint，与业务库 sys_user 无跨库 FK，用测试专用 ID） */
	private static final long MERGED_USER_FUNNEL = 80001L;
	private static final long MERGED_USER_RETENTION = 80002L;

	private static final long MIN_MS = 60000L;
	private static final long HOUR_MS = 3600000L;
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

		// 第二租户（自动初始化其 admin/123456 管理员，持租户内 * 通配）+ 低权用户（零权限角色）
		Map<String, Object> tenantBody = new HashMap<>();
		tenantBody.put("tenantName", "G103测试租户");
		tenantBody.put("contactUser", "G103测试");
		JsonNode tenantResp = readBody(post("/system/tenant/create", tenantBody, adminToken));
		assertThat(tenantResp.path("code").asInt()).as("建租户：" + tenantResp.path("msg").asText()).isEqualTo(200);
		tenantBCode = tenantResp.path("data").asText();
		tenantBToken = login(tenantBCode, ADMIN_USERNAME, ADMIN_PASSWORD);
		lowToken = createLowPrivilegeUser();

		seedApp(APP_FUNNEL, PLATFORM_TENANT);
		seedApp(APP_RETENTION, PLATFORM_TENANT);
		seedApp(APP_B, tenantBCode);

		// ---------- 漏斗夹具（t0 = 2 天前；事件窗口 [now-7d, now] 全覆盖） ----------
		long t0 = System.currentTimeMillis() - 2L * DAY_MS;
		List<TrackIngestEvent> funnelEvents = new ArrayList<>();
		// A 全转化（step2→step3 间穿插 $pageview——有序非紧邻，不影响链条）
		funnelEvents.add(ev(APP_FUNNEL, STEP1, t0, "it-g103-f-a"));
		funnelEvents.add(ev(APP_FUNNEL, STEP2, t0 + 30 * MIN_MS, "it-g103-f-a"));
		funnelEvents.add(ev(APP_FUNNEL, TrackConstants.EVENT_PAGEVIEW, t0 + 40 * MIN_MS, "it-g103-f-a"));
		funnelEvents.add(ev(APP_FUNNEL, STEP3, t0 + 50 * MIN_MS, "it-g103-f-a"));
		// B 止步第 2 步
		funnelEvents.add(ev(APP_FUNNEL, STEP1, t0 + 5 * MIN_MS, "it-g103-f-b"));
		funnelEvents.add(ev(APP_FUNNEL, STEP2, t0 + 35 * MIN_MS, "it-g103-f-b"));
		// C 第 2 步距 step1 30h——超 24h 窗不算（168h 窗内可转化）
		funnelEvents.add(ev(APP_FUNNEL, STEP1, t0, "it-g103-f-c"));
		funnelEvents.add(ev(APP_FUNNEL, STEP2, t0 + 30 * HOUR_MS, "it-g103-f-c"));
		// D 乱序（先 step2 后 step1——step2 早于 t1 永不进第 2 层）
		funnelEvents.add(ev(APP_FUNNEL, STEP2, t0 + 4 * HOUR_MS, "it-g103-f-d"));
		funnelEvents.add(ev(APP_FUNNEL, STEP1, t0 + 5 * HOUR_MS, "it-g103-f-d"));
		// E identity 归并：匿名期 ex + 登录期 ey 绑同一用户——归并后各层只计一次
		funnelEvents.add(ev(APP_FUNNEL, STEP1, t0 + 10 * MIN_MS, "it-g103-f-ex"));
		funnelEvents.add(ev(APP_FUNNEL, STEP2, t0 + 20 * MIN_MS, "it-g103-f-ex"));
		funnelEvents.add(ev(APP_FUNNEL, STEP3, t0 + 30 * MIN_MS, "it-g103-f-ex"));
		funnelEvents.add(ev(APP_FUNNEL, STEP1, t0 + 15 * MIN_MS, "it-g103-f-ey"));
		funnelEvents.add(ev(APP_FUNNEL, STEP2, t0 + 25 * MIN_MS, "it-g103-f-ey"));
		funnelEvents.add(ev(APP_FUNNEL, STEP3, t0 + 35 * MIN_MS, "it-g103-f-ey"));
		// F 中段 2h 跳：24h 窗全转化；1h 窗止步第 1 步
		funnelEvents.add(ev(APP_FUNNEL, STEP1, t0, "it-g103-f-f"));
		funnelEvents.add(ev(APP_FUNNEL, STEP2, t0 + 2 * HOUR_MS, "it-g103-f-f"));
		funnelEvents.add(ev(APP_FUNNEL, STEP3, t0 + 3 * HOUR_MS, "it-g103-f-f"));
		store.insertEvents(funnelEvents);
		store.upsertIdentities(List.of(
			new TrackEventStore.IdentityBinding(APP_FUNNEL, "it-g103-f-ex", MERGED_USER_FUNNEL, PLATFORM_TENANT),
			new TrackEventStore.IdentityBinding(APP_FUNNEL, "it-g103-f-ey", MERGED_USER_FUNNEL, PLATFORM_TENANT)));

		// B 租户应用（隔离正向用例：1 actor 两步转化，事件名与平台应用相同——跨租户不得串数）
		store.insertEvents(List.of(
			evTenant(APP_B, STEP1, t0, "it-g103-b-a", tenantBCode),
			evTenant(APP_B, STEP2, t0 + 30 * MIN_MS, "it-g103-b-a", tenantBCode)));

		// ---------- 留存夹具（UTC 日切；cohort 日 c1/c2/c3 = 前 3/2/1 天；回看窗首日 = 前 36 天） ----------
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		LocalDate c1 = today.minusDays(3);
		LocalDate c2 = today.minusDays(2);
		LocalDate c3 = today.minusDays(1);
		LocalDate lookbackFirst = today.minusDays(6L + TrackConstants.RETENTION_LOOKBACK_DAYS);
		List<TrackIngestEvent> retEvents = new ArrayList<>();
		// A：c1 新客，D0/D1/D2 回访
		retEvents.add(evDay(c1, "it-g103-r-a"));
		retEvents.add(evDay(c2, "it-g103-r-a"));
		retEvents.add(evDay(c3, "it-g103-r-a"));
		// B：c1 新客，D0/D2（D1 缺席）
		retEvents.add(evDay(c1, "it-g103-r-b"));
		retEvents.add(evDay(c3, "it-g103-r-b"));
		// C：c2 新客，D0/D1
		retEvents.add(evDay(c2, "it-g103-r-c"));
		retEvents.add(evDay(c3, "it-g103-r-c"));
		// D：c3 新客，仅 D0
		retEvents.add(evDay(c3, "it-g103-r-d"));
		// E：首活跃日 = 回看窗首日（截断无法判定新老，保守排除）；c1/c3 活跃不得贡献任何 cohort 行
		retEvents.add(evDay(lookbackFirst, "it-g103-r-e"));
		retEvents.add(evDay(c1, "it-g103-r-e"));
		retEvents.add(evDay(c3, "it-g103-r-e"));
		// F identity 归并：匿名 fx（c2 首活跃）+ 登录 fy（c3 活跃）绑同一用户——c2 新客 D0/D1 只计一次
		retEvents.add(evDay(c2, "it-g103-r-fx"));
		retEvents.add(evDay(c3, "it-g103-r-fy"));
		store.insertEvents(retEvents);
		store.upsertIdentities(List.of(
			new TrackEventStore.IdentityBinding(APP_RETENTION, "it-g103-r-fx", MERGED_USER_RETENTION, PLATFORM_TENANT),
			new TrackEventStore.IdentityBinding(APP_RETENTION, "it-g103-r-fy", MERGED_USER_RETENTION, PLATFORM_TENANT)));

		// 直灌为同步 JDBC，落库即断言夹具完整（防静默 ON CONFLICT 丢行）
		assertThat(trackLong("SELECT count(*) AS c FROM track_event WHERE app_key = ?", APP_FUNNEL))
			.as("漏斗夹具 19 事件").isEqualTo(19L);
		assertThat(trackLong("SELECT count(*) AS c FROM track_event WHERE app_key = ?", APP_RETENTION))
			.as("留存夹具 13 事件").isEqualTo(13L);
	}

	@AfterAll
	void cleanup() {
		// 物理删本类专用 appKey 的 track 域行（事件/identity/事件定义兜底/应用）
		DataSourceKey.use(TrackConstants.DS_KEY, () -> {
			Db.updateBySql("DELETE FROM track_event WHERE app_key IN (?, ?, ?)", APP_FUNNEL, APP_RETENTION, APP_B);
			Db.updateBySql("DELETE FROM track_identity WHERE app_key IN (?, ?, ?)", APP_FUNNEL, APP_RETENTION, APP_B);
			Db.updateBySql("DELETE FROM track_event_def WHERE app_key IN (?, ?, ?)", APP_FUNNEL, APP_RETENTION, APP_B);
			Db.updateBySql("DELETE FROM track_app WHERE app_key IN (?, ?, ?)", APP_FUNNEL, APP_RETENTION, APP_B);
		});
	}

	// ==================== 漏斗 ====================

	/** ① 三步有序转化精确值（默认 24h 窗）：s1=6/s2=4/s3=3——C 超窗、D 乱序不进层、E 归并只计一次、A 穿插事件不断链 */
	@Test
	void funnelOrderedConversionCounts() {
		JsonNode data = readBodyAssertOk(get("/system/track/funnel?appKey=" + APP_FUNNEL
			+ "&steps=" + STEPS_CSV + "&days=7", adminToken));
		JsonNode steps = data.path("steps");
		assertThat(steps.size()).isEqualTo(3);
		assertThat(steps.get(0).path("eventName").asText()).isEqualTo(STEP1);
		assertThat(steps.get(0).path("count").asLong()).as("step1 = A/B/C/D/E归并/F = 6").isEqualTo(6L);
		assertThat(steps.get(1).path("eventName").asText()).isEqualTo(STEP2);
		assertThat(steps.get(1).path("count").asLong()).as("step2 = A/B/E/F（C 超窗、D 乱序）= 4").isEqualTo(4L);
		assertThat(steps.get(2).path("eventName").asText()).isEqualTo(STEP3);
		assertThat(steps.get(2).path("count").asLong()).as("step3 = A/E/F = 3").isEqualTo(3L);
		assertThat(data.path("actor").asText()).as("identity 归并口径").isEqualTo("merged");
		assertThat(data.path("days").asInt()).isEqualTo(7);
		assertThat(data.path("windowHours").asLong()).as("默认 24h")
			.isEqualTo(TrackConstants.FUNNEL_WINDOW_DEFAULT_HOURS);
	}

	/** ② 转化窗口分档：1h → 6/3/2（F 中段 2h 跳出局）；168h → 6/5/3（C 的 30h 跳入窗） */
	@Test
	void funnelConversionWindowOptions() {
		JsonNode oneHour = readBodyAssertOk(get("/system/track/funnel?appKey=" + APP_FUNNEL
			+ "&steps=" + STEPS_CSV + "&days=7&windowHours=1", adminToken));
		assertThat(oneHour.path("windowHours").asLong()).isEqualTo(1L);
		JsonNode steps1 = oneHour.path("steps");
		assertThat(steps1.get(0).path("count").asLong()).isEqualTo(6L);
		assertThat(steps1.get(1).path("count").asLong()).as("1h 窗 step2 = A/B/E（F 2h 跳出局）= 3").isEqualTo(3L);
		assertThat(steps1.get(2).path("count").asLong()).as("1h 窗 step3 = A/E = 2").isEqualTo(2L);

		JsonNode week = readBodyAssertOk(get("/system/track/funnel?appKey=" + APP_FUNNEL
			+ "&steps=" + STEPS_CSV + "&days=7&windowHours=168", adminToken));
		JsonNode steps168 = week.path("steps");
		assertThat(steps168.get(0).path("count").asLong()).isEqualTo(6L);
		assertThat(steps168.get(1).path("count").asLong()).as("168h 窗 step2 = A/B/C/E/F = 5").isEqualTo(5L);
		assertThat(steps168.get(2).path("count").asLong()).as("168h 窗 step3 = A/E/F（C 无 step3）= 3").isEqualTo(3L);
	}

	/** ③ 参数 400：单步/去重后单步/6 步/非法事件名/非法 $ 事件；$pageview 预定义名合法 200；windowHours=5 → 400 */
	@Test
	void funnelParamValidation() {
		JsonNode one = readBody(get(funnelUrl("&steps=" + STEP1), adminToken));
		assertThat(one.path("code").asInt()).as("单步应 400").isEqualTo(400);
		assertThat(one.path("msg").asText()).contains("步骤数");

		JsonNode dup = readBody(get(funnelUrl("&steps=" + STEP1 + ", " + STEP1 + "," + STEP1), adminToken));
		assertThat(dup.path("code").asInt()).as("去重后单步应 400").isEqualTo(400);

		JsonNode six = readBody(get(funnelUrl("&steps=e1,e2,e3,e4,e5,e6"), adminToken));
		assertThat(six.path("code").asInt()).as("6 步应 400").isEqualTo(400);

		JsonNode badName = readBody(get(funnelUrl("&steps=" + STEP1 + ",bad-name"), adminToken));
		assertThat(badName.path("code").asInt()).as("非法事件名应 400").isEqualTo(400);
		assertThat(badName.path("msg").asText()).contains("步骤事件名不合法");

		JsonNode badDollar = readBody(get(funnelUrl("&steps=" + STEP1 + ",$hack"), adminToken));
		assertThat(badDollar.path("code").asInt()).as("非白名单 $ 事件应 400").isEqualTo(400);

		JsonNode badWindow = readBody(get(funnelUrl("&steps=" + STEPS_CSV + "&windowHours=5"), adminToken));
		assertThat(badWindow.path("code").asInt()).as("白名单外窗口应 400").isEqualTo(400);
		assertThat(badWindow.path("msg").asText()).contains("转化窗口");

		JsonNode predefined = readBodyAssertOk(get(funnelUrl("&steps=" + STEP1 + "," + TrackConstants.EVENT_PAGEVIEW), adminToken));
		assertThat(predefined.path("steps").size()).as("$pageview 预定义事件名合法").isEqualTo(2);
	}

	// ==================== 留存 ====================

	/** ④ cohort 网格逐格精确值：c1=2{0:2,1:1,2:2} / c2=2{0:2,1:2} / c3=1{0:1}；
	 *  E_trunc 截断排除（c1 规模 2 非 3）、F 归并去重（c2 规模 2 非 3、c3 规模 1 非 2） */
	@Test
	void retentionCohortGridExact() {
		JsonNode data = readBodyAssertOk(get("/system/track/retention?appKey=" + APP_RETENTION + "&days=7", adminToken));
		assertThat(data.path("days").asInt()).isEqualTo(7);
		JsonNode rows = data.path("rows");
		assertThat(rows.size()).as("3 个 cohort 日（其余窗口日无新客不出行）").isEqualTo(3);
		LocalDate today = LocalDate.now(ZoneOffset.UTC);

		JsonNode r1 = rows.get(0);
		assertThat(r1.path("cohortDate").asText()).isEqualTo(today.minusDays(3).toString());
		assertThat(r1.path("cohortSize").asLong()).as("c1 新客 = A/B（E_trunc 截断排除）").isEqualTo(2L);
		assertThat(r1.path("retained").path("0").asLong()).isEqualTo(2L);
		assertThat(r1.path("retained").path("1").asLong()).as("D+1 仅 A 回访").isEqualTo(1L);
		assertThat(r1.path("retained").path("2").asLong()).isEqualTo(2L);
		assertThat(r1.path("retained").size()).as("c1 仅 D0..D2 有回访").isEqualTo(3);

		JsonNode r2 = rows.get(1);
		assertThat(r2.path("cohortDate").asText()).isEqualTo(today.minusDays(2).toString());
		assertThat(r2.path("cohortSize").asLong()).as("c2 新客 = C + F归并（归并不重复计）").isEqualTo(2L);
		assertThat(r2.path("retained").path("0").asLong()).isEqualTo(2L);
		assertThat(r2.path("retained").path("1").asLong()).as("D+1 = C 与 F 均回访").isEqualTo(2L);
		assertThat(r2.path("retained").size()).isEqualTo(2);

		JsonNode r3 = rows.get(2);
		assertThat(r3.path("cohortDate").asText()).isEqualTo(today.minusDays(1).toString());
		assertThat(r3.path("cohortSize").asLong()).as("c3 新客 = D").isEqualTo(1L);
		assertThat(r3.path("retained").path("0").asLong()).isEqualTo(1L);
		assertThat(r3.path("retained").size()).isEqualTo(1);
	}

	/** ⑤ days 钳制与缺省：days=100 → 钳 30（网格不变——回看窗更深仍排除非新客）；缺省 → 7 */
	@Test
	void retentionDaysClampAndDefaults() {
		JsonNode clamped = readBodyAssertOk(get("/system/track/retention?appKey=" + APP_RETENTION + "&days=100", adminToken));
		assertThat(clamped.path("days").asInt()).as("钳到 RETENTION_DAYS_MAX").isEqualTo(TrackConstants.RETENTION_DAYS_MAX);
		JsonNode rows = clamped.path("rows");
		assertThat(rows.size()).as("钳后 cohort 网格不变").isEqualTo(3);
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		assertThat(rows.get(0).path("cohortDate").asText()).isEqualTo(today.minusDays(3).toString());
		assertThat(rows.get(0).path("cohortSize").asLong()).isEqualTo(2L);
		assertThat(rows.get(0).path("retained").path("2").asLong()).isEqualTo(2L);

		JsonNode def = readBodyAssertOk(get("/system/track/retention?appKey=" + APP_RETENTION, adminToken));
		assertThat(def.path("days").asInt()).as("缺省 7 天").isEqualTo(7);
		assertThat(def.path("rows").size()).isEqualTo(3);
	}

	// ==================== 鉴权与租户隔离 ====================

	/** ⑥ 401（无 token 双端点）/403（低权双端点）/跨租户 400（assertAppVisible 双端点）/
	 *  租户隔离正向（B 租户查本租户应用仅见本租户数据，事件名相同不串数） */
	@Test
	void accessControlAndTenantIsolation() {
		String funnelUrl = funnelUrl("&steps=" + STEPS_CSV + "&days=7");
		String retentionUrl = "/system/track/retention?appKey=" + APP_RETENTION + "&days=7";
		assertThat(get(funnelUrl, null).getStatusCode().value()).as("无 token funnel 应 401").isEqualTo(401);
		assertThat(get(retentionUrl, null).getStatusCode().value()).as("无 token retention 应 401").isEqualTo(401);
		assertThat(get(funnelUrl, lowToken).getStatusCode().value()).as("低权 funnel 应 403").isEqualTo(403);
		assertThat(get(retentionUrl, lowToken).getStatusCode().value()).as("低权 retention 应 403").isEqualTo(403);

		JsonNode crossFunnel = readBody(get(funnelUrl, tenantBToken));
		assertThat(crossFunnel.path("code").asInt()).as("跨租户 funnel 应 400（应用归属校验）").isEqualTo(400);
		JsonNode crossRetention = readBody(get(retentionUrl, tenantBToken));
		assertThat(crossRetention.path("code").asInt()).as("跨租户 retention 应 400").isEqualTo(400);

		// B 租户查本租户应用：只见自己的 1 actor（平台 6 actor 同名事件不串入）
		JsonNode own = readBodyAssertOk(get("/system/track/funnel?appKey=" + APP_B
			+ "&steps=" + STEPS_CSV + "&days=7", tenantBToken));
		JsonNode ownSteps = own.path("steps");
		assertThat(ownSteps.get(0).path("count").asLong()).as("B 租户 step1 仅本租户 1").isEqualTo(1L);
		assertThat(ownSteps.get(1).path("count").asLong()).isEqualTo(1L);
		assertThat(ownSteps.get(2).path("count").asLong()).as("B 租户 actor 无 step3").isEqualTo(0L);
	}

	// ---------- 测试工具 ----------

	private String funnelUrl(String query) {
		return "/system/track/funnel?appKey=" + APP_FUNNEL + query;
	}

	private JsonNode readBodyAssertOk(ResponseEntity<String> resp) {
		JsonNode json = readBody(resp);
		assertThat(json.path("code").asInt()).as("请求应成功：" + json).isEqualTo(200);
		return json.path("data");
	}

	/** 测试应用直灌（幂等 INSERT ON CONFLICT，与 TrackAnalysisApiTest 同写法） */
	private void seedApp(String appKey, String tenantId) {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(
			"INSERT INTO track_app (id, app_key, app_name, platform, tenant_id, sample_rate, enabled,"
				+ " create_time, update_time, is_deleted) VALUES (?, ?, ?, 'web', ?, 100, 1, now(), now(), 0)"
				+ " ON CONFLICT (app_key) WHERE is_deleted = 0 DO UPDATE SET tenant_id = EXCLUDED.tenant_id, enabled = 1",
			IdUtil.getSnowflakeNextId(), appKey, "G103测试-" + appKey, tenantId));
	}

	/** 平台租户直灌事件（receivedAtMs 精确控时） */
	private TrackIngestEvent ev(String appKey, String name, long ms, String distinctId) {
		return evTenant(appKey, name, ms, distinctId, PLATFORM_TENANT);
	}

	private TrackIngestEvent evTenant(String appKey, String name, long ms, String distinctId, String tenantId) {
		TrackIngestEvent e = new TrackIngestEvent();
		e.setEventId(UUID.randomUUID().toString());
		e.setAppKey(appKey);
		e.setEventName(name);
		e.setClientTsMs(ms);
		e.setTsMs(ms);
		e.setReceivedAtMs(ms);
		e.setClockSkewed(0);
		e.setDistinctId(distinctId);
		e.setSessionId("s-" + distinctId);
		e.setTenantId(tenantId);
		e.setUrlPath("/it");
		e.setRoutePath("/it");
		e.setDevice("desktop");
		e.setPropsJson("{}");
		return e;
	}

	/** 留存夹具事件（当日 UTC 正午，防日切边界漂移；活跃 = 任意事件，用 $pageview） */
	private TrackIngestEvent evDay(LocalDate day, String distinctId) {
		long ms = day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
		return ev(APP_RETENTION, TrackConstants.EVENT_PAGEVIEW, ms, distinctId);
	}

	/** 建零权限角色 + 低权用户并授权（TrackUserTimelineApiTest 同款），返回其 token */
	private String createLowPrivilegeUser() {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "G103零权限角色");
		role.put("roleCode", ROLE_CODE);
		role.put("sort", 99);
		role.put("dataScope", 1);
		JsonNode roleResp = readBody(post("/system/role/submit", role, adminToken));
		assertThat(roleResp.path("code").asInt()).as("建角色：" + roleResp.path("msg").asText()).isEqualTo(200);

		Map<String, Object> user = new HashMap<>();
		user.put("username", LOW_USERNAME);
		user.put("nickname", "G103低权用户");
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
}
