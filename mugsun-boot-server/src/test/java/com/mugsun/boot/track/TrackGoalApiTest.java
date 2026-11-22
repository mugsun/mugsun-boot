package com.mugsun.boot.track;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mybatisflex.core.datasource.DataSourceKey;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
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
 * 目标转化（{@code /system/track/goal/**}）的集成测试。
 *
 * <p>主测试数据是平台租户一个应用下的 6 个访客（括号里是特意加进去的干扰）：
 * <pre>
 * v1  浏览 /home → 浏览 /order/success → pay_success × 2 → 浏览 /product/1（路由模板 /product/:id）
 * v2  浏览 /home → pay_success → 浏览 /product/2（路由模板 /product/:id）
 * v3  浏览 /home → 浏览 /order/success × 2
 * v4  浏览 /home
 * v5  （只有一个自定义事件 add_cart，地址是 /order/success，它不是页面浏览）
 * v6  浏览 /order/success/detail（只有原始路径，没有路由模板）
 * </pre>
 * 按这组数据，6 个目标算出来是（全部访客 6 人）：
 * <pre>
 * 订单相关页面    页面 /order/*        3 人（v1、v3、v6）4 次  50%
 * 支付成功        事件 pay_success     2 人（v1、v2）    3 次  33.33%
 * 到达下单成功页  页面 /order/success  2 人（v1、v3）    3 次  33.33%
 * 看商品          页面 /product/:id    2 人（v1、v2）    2 次  33.33%
 * 看 1 号商品     页面 /product/1      1 人（v1）        1 次  16.67%
 * 从没触发过      事件 never_fired     0 人              0 次  0
 * </pre>
 * 逐项核对：
 * <ul>
 *   <li>转化人数、次数、转化率和排序；全部访客数和数据概览的访客数一致；</li>
 *   <li>页面目标只算页面浏览（v5 的自定义事件不算）；路由模板或原始路径对上任何一个都算；
 *       末尾带 * 按地址开头匹配（v6），不带 * 要整个地址一样；</li>
 *   <li>统计范围外的数据不算；</li>
 *   <li>新增、编辑、删除以后马上按新的定义算，不用等缓存过期；编辑时没传的字段保持原样；</li>
 *   <li>入参不对返回 400 并说明怎么改，英文请求返回英文提示；一个应用最多 20 个目标；</li>
 *   <li>没登录 401、没有权限 403、各租户只看得到、改得了自己的目标，目标的归属租户跟着应用走。</li>
 * </ul>
 * 事件都放在两天前（相对现在计算，不写死日期）：近 7 天看得到，只看今天看不到。
 * 用到的应用标识是本测试专用的，测完按应用标识删掉目标、事件和应用。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrackGoalApiTest extends AbstractTrackIntegrationTest {

	private static final String APP = "ak_test_goal_01";
	private static final String APP_EDIT = "ak_test_goal_edit";
	private static final String APP_LIMIT = "ak_test_goal_limit";
	private static final String APP_B = "ak_test_goal_b";
	private static final String LOW_USERNAME = "it_goal_lowpriv";
	private static final String LOW_PASSWORD = NEW_USER_PASSWORD;
	private static final String ROLE_CODE = "it-goal-noperm";

	private static final String PAGEVIEW = TrackConstants.EVENT_PAGEVIEW;
	private static final String HOME = "/home";
	private static final String ORDER_OK = "/order/success";

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
		tenantBody.put("tenantName", "目标转化测试租户");
		tenantBody.put("contactUser", "目标转化测试");
		JsonNode tenantResp = readBody(post("/system/tenant/create", tenantBody, adminToken));
		assertThat(tenantResp.path("code").asInt()).as("建租户：" + tenantResp.path("msg").asText()).isEqualTo(200);
		tenantBCode = tenantCodeOf(tenantResp);
		normalizeTenantAdminPassword(tenantBCode);
		tenantBToken = login(tenantBCode, ADMIN_USERNAME, ADMIN_PASSWORD);
		lowToken = createLowPrivilegeUser();

		seedApp(APP, PLATFORM_TENANT);
		seedApp(APP_EDIT, PLATFORM_TENANT);
		seedApp(APP_LIMIT, PLATFORM_TENANT);
		seedApp(APP_B, tenantBCode);

		long t0 = System.currentTimeMillis() - 2L * DAY_MS;
		List<TrackIngestEvent> events = new ArrayList<>();
		// v1：下单成功页看了 1 次，支付事件触发了 2 次，商品页的路由模板和原始路径不一样
		events.add(event(APP, PLATFORM_TENANT, "it-goal-v1", PAGEVIEW, t0, HOME, HOME));
		events.add(event(APP, PLATFORM_TENANT, "it-goal-v1", PAGEVIEW, t0 + MIN_MS, ORDER_OK, ORDER_OK));
		events.add(event(APP, PLATFORM_TENANT, "it-goal-v1", "pay_success", t0 + 2 * MIN_MS, ORDER_OK, ORDER_OK));
		events.add(event(APP, PLATFORM_TENANT, "it-goal-v1", "pay_success", t0 + 3 * MIN_MS, ORDER_OK, ORDER_OK));
		events.add(event(APP, PLATFORM_TENANT, "it-goal-v1", PAGEVIEW, t0 + 4 * MIN_MS, "/product/:id", "/product/1"));
		// v2：支付事件 1 次，看的是 2 号商品
		events.add(event(APP, PLATFORM_TENANT, "it-goal-v2", PAGEVIEW, t0, HOME, HOME));
		events.add(event(APP, PLATFORM_TENANT, "it-goal-v2", "pay_success", t0 + MIN_MS, HOME, HOME));
		events.add(event(APP, PLATFORM_TENANT, "it-goal-v2", PAGEVIEW, t0 + 2 * MIN_MS, "/product/:id", "/product/2"));
		// v3：下单成功页看了 2 次，算 1 个人、算 2 次
		events.add(event(APP, PLATFORM_TENANT, "it-goal-v3", PAGEVIEW, t0, HOME, HOME));
		events.add(event(APP, PLATFORM_TENANT, "it-goal-v3", PAGEVIEW, t0 + MIN_MS, ORDER_OK, ORDER_OK));
		events.add(event(APP, PLATFORM_TENANT, "it-goal-v3", PAGEVIEW, t0 + 2 * MIN_MS, ORDER_OK, ORDER_OK));
		// v4：只看了首页，哪个目标都没达成，但算进全部访客
		events.add(event(APP, PLATFORM_TENANT, "it-goal-v4", PAGEVIEW, t0, HOME, HOME));
		// v5：自定义事件的地址是下单成功页，但它不是页面浏览，页面目标不算
		events.add(event(APP, PLATFORM_TENANT, "it-goal-v5", "add_cart", t0, ORDER_OK, ORDER_OK));
		// v6：只有原始路径，地址以 /order/ 开头：带 * 的目标算，不带 * 的不算
		events.add(event(APP, PLATFORM_TENANT, "it-goal-v6", PAGEVIEW, t0, "", ORDER_OK + "/detail"));
		// 编辑用的应用：首页 2 人 3 次，加购 1 人 1 次，一共 3 个访客
		events.add(event(APP_EDIT, PLATFORM_TENANT, "it-goal-e1", PAGEVIEW, t0, HOME, HOME));
		events.add(event(APP_EDIT, PLATFORM_TENANT, "it-goal-e1", PAGEVIEW, t0 + MIN_MS, HOME, HOME));
		events.add(event(APP_EDIT, PLATFORM_TENANT, "it-goal-e2", "add_cart", t0, HOME, HOME));
		events.add(event(APP_EDIT, PLATFORM_TENANT, "it-goal-e3", PAGEVIEW, t0, HOME, HOME));
		// 另一个租户的应用：1 个访客，事件名和平台应用有重合，两边的数不能串
		events.add(event(APP_B, tenantBCode, "it-goal-b1", PAGEVIEW, t0, HOME, HOME));
		events.add(event(APP_B, tenantBCode, "it-goal-b1", "pay_success", t0 + MIN_MS, HOME, HOME));
		store.insertEvents(events);

		assertThat(trackLong("SELECT count(*) AS c FROM track_event WHERE app_key = ?", APP))
			.as("平台应用的 14 条测试事件都写进去了").isEqualTo(14L);
		assertThat(trackLong("SELECT count(*) AS c FROM track_event WHERE app_key IN (?, ?)", APP_EDIT, APP_B))
			.as("另外两个应用的 6 条测试事件都写进去了").isEqualTo(6L);

		// 建的先后就是转化人数一样时的排序
		created(submit(adminToken, pageGoal(APP, "订单相关页面", "/order/*")));
		created(submit(adminToken, eventGoal(APP, "支付成功", "pay_success")));
		created(submit(adminToken, pageGoal(APP, "到达下单成功页", ORDER_OK)));
		created(submit(adminToken, pageGoal(APP, "看商品", "/product/:id")));
		created(submit(adminToken, pageGoal(APP, "看 1 号商品", "/product/1")));
		created(submit(adminToken, eventGoal(APP, "从没触发过", "never_fired")));
	}

	@AfterAll
	void cleanup() {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> {
			Db.updateBySql("DELETE FROM track_goal WHERE app_key IN (?, ?, ?, ?)", APP, APP_EDIT, APP_LIMIT, APP_B);
			Db.updateBySql("DELETE FROM track_event WHERE app_key IN (?, ?, ?, ?)", APP, APP_EDIT, APP_LIMIT, APP_B);
			Db.updateBySql("DELETE FROM track_app WHERE app_key IN (?, ?, ?, ?)", APP, APP_EDIT, APP_LIMIT, APP_B);
		});
	}

	// ==================== 转化数据 ====================

	/** 每个目标的人数、次数、转化率都对；按人数从多到少，人数一样按建的先后 */
	@Test
	void conversionsCountVisitorsAndTimesPerGoal() {
		JsonNode data = ok(conversions(adminToken, APP, "&days=7"));
		assertThat(data.path("days").asInt()).isEqualTo(7);
		assertThat(data.path("uv").asLong()).as("全部访客：v1 到 v6").isEqualTo(6L);
		assertThat(rows(data)).containsExactly(
			"订单相关页面 uv=3 count=4 rate=0.5",
			"支付成功 uv=2 count=3 rate=0.3333",
			"到达下单成功页 uv=2 count=3 rate=0.3333",
			"看商品 uv=2 count=2 rate=0.3333",
			"看 1 号商品 uv=1 count=1 rate=0.1667",
			"从没触发过 uv=0 count=0 rate=0.0");

		JsonNode page = goalByName(data, "订单相关页面");
		assertThat(page.path("goalType").asText()).isEqualTo("page");
		assertThat(page.path("pagePath").asText()).isEqualTo("/order/*");
		assertThat(page.path("eventName").isNull()).as("页面目标没有事件名").isTrue();
		assertThat(page.path("id").isTextual()).as("编号按字符串给，浏览器里不丢精度").isTrue();
		assertThat(page.path("id").asText()).matches("\\d+");

		JsonNode event = goalByName(data, "支付成功");
		assertThat(event.path("goalType").asText()).isEqualTo("event");
		assertThat(event.path("eventName").asText()).isEqualTo("pay_success");
		assertThat(event.path("pagePath").isNull()).as("事件目标没有页面地址").isTrue();
	}

	/** 全部访客数和数据概览是同一个算法，同一个范围两边一样 */
	@Test
	void visitorCountMatchesOverview() {
		long overviewUv = readBody(get("/system/track/overview?appKey=" + APP + "&days=7", adminToken))
			.path("data").path("cards").path("uv").asLong();
		assertThat(ok(conversions(adminToken, APP, "&days=7")).path("uv").asLong())
			.as("和数据概览的访客数一致").isEqualTo(overviewUv).isEqualTo(6L);
	}

	/** 不传天数按 7 天；只看今天时两天前的数据不算，目标还在，数都是 0 */
	@Test
	void outOfRangeDataIsNotCounted() {
		assertThat(ok(conversions(adminToken, APP, "")).path("days").asInt()).as("不传天数按 7 天").isEqualTo(7);

		JsonNode today = ok(conversions(adminToken, APP, "&days=1"));
		assertThat(today.path("uv").asLong()).isZero();
		assertThat(today.path("goals").size()).as("目标还是 6 个").isEqualTo(6);
		for (JsonNode goal : today.path("goals")) {
			assertThat(goal.path("uv").asLong()).isZero();
			assertThat(goal.path("count").asLong()).isZero();
			assertThat(goal.path("rate").asDouble()).as("没有访客时转化率是 0，不报错").isZero();
		}
	}

	// ==================== 新增、编辑、删除 ====================

	/** 改了目标马上按新的定义算；编辑时没传的字段保持原样；删掉以后名称可以再用 */
	@Test
	void editAndRemoveTakeEffectImmediately() {
		assertThat(ok(conversions(adminToken, APP_EDIT, "&days=7")).path("goals").size()).as("一开始没有目标").isZero();

		JsonNode saved = readBody(submit(adminToken, eventGoal(APP_EDIT, "临时目标", "add_cart")));
		assertThat(saved.path("code").asInt()).isEqualTo(200);
		assertThat(saved.path("msg").asText()).isEqualTo("已保存目标「临时目标」。以前的数据也按这个条件算");
		JsonNode first = ok(conversions(adminToken, APP_EDIT, "&days=7"));
		assertThat(first.path("uv").asLong()).isEqualTo(3L);
		assertThat(rows(first)).containsExactly("临时目标 uv=1 count=1 rate=0.3333");
		String id = goalByName(first, "临时目标").path("id").asText();

		// 换成页面目标：不用等缓存过期，马上按页面算；原来的事件名清掉
		Map<String, Object> toPage = new HashMap<>();
		toPage.put("id", id);
		toPage.put("goalType", "page");
		toPage.put("pagePath", HOME);
		created(submit(adminToken, toPage));
		JsonNode second = ok(conversions(adminToken, APP_EDIT, "&days=7"));
		assertThat(rows(second)).containsExactly("临时目标 uv=2 count=3 rate=0.6667");
		assertThat(goalByName(second, "临时目标").path("eventName").isNull()).isTrue();
		assertThat(goalByName(second, "临时目标").path("pagePath").asText()).isEqualTo(HOME);

		// 只改名称：类型和条件不动。编号按数字传也可以
		Map<String, Object> rename = new HashMap<>();
		rename.put("id", Long.parseLong(id));
		rename.put("goalName", "改过名字");
		created(submit(adminToken, rename));
		assertThat(rows(ok(conversions(adminToken, APP_EDIT, "&days=7"))))
			.containsExactly("改过名字 uv=2 count=3 rate=0.6667");

		// 换回事件目标但没给事件名：原来的事件名已经清掉了，要重新填；这次没改成
		Map<String, Object> toEvent = new HashMap<>();
		toEvent.put("id", id);
		toEvent.put("goalType", "event");
		assertRejected(submit(adminToken, toEvent), "请填写事件名");
		assertThat(goalByName(ok(conversions(adminToken, APP_EDIT, "&days=7")), "改过名字").path("goalType").asText())
			.isEqualTo("page");

		// 应用不能改：编辑时传了别的应用也不看
		Map<String, Object> moveApp = new HashMap<>();
		moveApp.put("id", id);
		moveApp.put("appKey", APP);
		created(submit(adminToken, moveApp));
		assertThat(ok(conversions(adminToken, APP_EDIT, "&days=7")).path("goals").size()).isEqualTo(1);
		assertThat(ok(conversions(adminToken, APP, "&days=7")).path("goals").size()).as("没有挪到别的应用去").isEqualTo(6);

		JsonNode removed = readBody(remove(adminToken, id));
		assertThat(removed.path("code").asInt()).isEqualTo(200);
		assertThat(removed.path("msg").asText()).isEqualTo("已删除目标「改过名字」。已经上报的事件不受影响");
		JsonNode afterRemove = ok(conversions(adminToken, APP_EDIT, "&days=7"));
		assertThat(afterRemove.path("goals").size()).as("删掉以后马上不再出现").isZero();
		assertThat(afterRemove.path("uv").asLong()).as("事件还在，访客数不变").isEqualTo(3L);
		assertRejected(remove(adminToken, id), "这个目标不存在");
		assertRejected(submit(adminToken, rename), "这个目标不存在");

		// 删掉的目标不占名称
		created(submit(adminToken, pageGoal(APP_EDIT, "改过名字", HOME)));
		String again = goalByName(ok(conversions(adminToken, APP_EDIT, "&days=7")), "改过名字").path("id").asText();
		assertThat(again).isNotEqualTo(id);
		assertThat(readBody(remove(adminToken, again)).path("code").asInt()).isEqualTo(200);
	}

	/** 一个应用最多 20 个目标；删掉一个又能加 */
	@Test
	void goalLimitPerApp() {
		for (int i = 1; i <= TrackConstants.GOAL_MAX_PER_APP; i++) {
			created(submit(adminToken, eventGoal(APP_LIMIT, "目标 " + i, "limit_event_" + i)));
		}
		assertRejected(submit(adminToken, eventGoal(APP_LIMIT, "第 21 个", "limit_event_21")),
			"一个应用最多 20 个目标。请先删掉不用的再新增");

		JsonNode data = ok(conversions(adminToken, APP_LIMIT, "&days=7"));
		assertThat(data.path("goals").size()).isEqualTo(20);
		assertThat(data.path("goals").get(0).path("goalName").asText()).as("都是 0 人时按建的先后").isEqualTo("目标 1");
		assertThat(readBody(remove(adminToken, goalByName(data, "目标 1").path("id").asText())).path("code").asInt())
			.isEqualTo(200);
		created(submit(adminToken, eventGoal(APP_LIMIT, "第 21 个", "limit_event_21")));
	}

	// ==================== 入参校验 ====================

	/** 入参不对都返回 400，提示里说明怎么改；被拒绝的请求不留下目标 */
	@Test
	void validationMessagesSayWhatToFix() {
		assertRejected(get("/system/track/goal/conversions", adminToken), "请先选择应用。转化目标是按应用分开的");
		assertRejected(conversions(adminToken, "ak_test_goal_none", ""), "这个应用不存在");
		assertRejected(conversions(adminToken, APP, "&days=0"), "统计天数只支持 1 到 30 天");
		assertRejected(conversions(adminToken, APP, "&days=31"), "统计天数只支持 1 到 30 天");

		assertRejected(post("/system/track/goal/submit", new HashMap<>(), adminToken), "请先选择应用");
		assertRejected(submit(adminToken, eventGoal("ak_test_goal_none", "目标", "some_event")), "这个应用不存在");
		assertRejected(submit(adminToken, eventGoal(APP, "  ", "some_event")), "请填写目标名称");
		assertRejected(submit(adminToken, eventGoal(APP, "名".repeat(51), "some_event")), "目标名称请控制在 50 个字以内");
		assertRejected(submit(adminToken, goal(APP, "类型不对", "click", "some_event", null)), "目标类型只支持触发事件和访问页面");
		assertRejected(submit(adminToken, goal(APP, "没选类型", null, "some_event", null)), "目标类型只支持触发事件和访问页面");
		assertRejected(submit(adminToken, eventGoal(APP, "没填事件", " ")), "请填写事件名。触发这个事件就算达成目标");
		assertRejected(submit(adminToken, eventGoal(APP, "保留事件", "$pageview")), "事件名请用字母开头");
		assertRejected(submit(adminToken, pageGoal(APP, "没填地址", null)), "请填写页面地址。访问这个页面就算达成目标");
		assertRejected(submit(adminToken, pageGoal(APP, "没有斜杠", "order/success")), "页面地址请以 / 开头");
		assertRejected(submit(adminToken, pageGoal(APP, "地址太长", "/" + "a".repeat(255))), "页面地址请控制在 255 个字以内");
		assertRejected(submit(adminToken, pageGoal(APP, "星号在中间", "/order/*/detail")), "* 只能放在末尾");
		assertRejected(submit(adminToken, pageGoal(APP, "两个星号", "/order/**")), "* 只能放在末尾");
		assertRejected(submit(adminToken, eventGoal(APP, "支付成功", "other_event")), "已经有同名的目标了。请换一个名称");

		// 编辑：改成别的目标已经用了的名称不行，改成自己现在的名称可以
		JsonNode data = ok(conversions(adminToken, APP, "&days=7"));
		String payId = goalByName(data, "支付成功").path("id").asText();
		Map<String, Object> clash = new HashMap<>();
		clash.put("id", payId);
		clash.put("goalName", "看商品");
		assertRejected(submit(adminToken, clash), "已经有同名的目标了");
		Map<String, Object> sameName = new HashMap<>();
		sameName.put("id", payId);
		sameName.put("goalName", "支付成功");
		created(submit(adminToken, sameName));

		Map<String, Object> badId = new HashMap<>();
		badId.put("id", "abc");
		badId.put("goalName", "编号不对");
		assertRejected(submit(adminToken, badId), "目标编号请填写数字");
		badId.put("id", "9999999999999999999");
		assertRejected(submit(adminToken, badId), "目标编号请填写数字");
		badId.put("id", "1");
		assertRejected(submit(adminToken, badId), "这个目标不存在。请刷新列表后再试");

		assertRejected(post("/system/track/goal/remove", new HashMap<>(), adminToken), "请先在列表里选择要删除的目标");
		assertRejected(remove(adminToken, "abc"), "目标编号请填写数字");
		assertRejected(remove(adminToken, "1"), "这个目标不存在。请刷新列表后再试");

		assertThat(ok(conversions(adminToken, APP, "&days=7")).path("goals").size())
			.as("被拒绝的请求没有留下目标").isEqualTo(6);
	}

	/** 英文请求：拒绝的提示和保存成功的提示都是英文，数量分单复数 */
	@Test
	void messagesFollowTheRequestLanguage() {
		HttpHeaders english = authHeaders(adminToken);
		english.set(HttpHeaders.ACCEPT_LANGUAGE, "en");

		JsonNode noName = readBody(rest.exchange("/system/track/goal/submit", HttpMethod.POST,
			new HttpEntity<>(eventGoal(APP, "", "some_event"), english), String.class));
		assertThat(noName.path("code").asInt()).isEqualTo(400);
		assertThat(noName.path("msg").asText()).isEqualTo("Please enter the goal name");

		JsonNode tooLong = readBody(rest.exchange("/system/track/goal/submit", HttpMethod.POST,
			new HttpEntity<>(eventGoal(APP, "n".repeat(51), "some_event"), english), String.class));
		assertThat(tooLong.path("msg").asText()).isEqualTo("Please keep the goal name within 50 characters");

		JsonNode noId = readBody(rest.exchange("/system/track/goal/remove", HttpMethod.POST,
			new HttpEntity<>(new HashMap<>(), english), String.class));
		assertThat(noId.path("msg").asText()).isEqualTo("Please select the goal to delete from the list first");

		JsonNode saved = readBody(rest.exchange("/system/track/goal/submit", HttpMethod.POST,
			new HttpEntity<>(eventGoal(APP_EDIT, "English goal", "some_event"), english), String.class));
		assertThat(saved.path("code").asInt()).isEqualTo(200);
		assertThat(saved.path("msg").asText())
			.isEqualTo("Saved goal \"English goal\". Earlier data is counted with this condition too.");
		String id = goalByName(ok(conversions(adminToken, APP_EDIT, "&days=7")), "English goal").path("id").asText();
		JsonNode removed = readBody(rest.exchange("/system/track/goal/remove", HttpMethod.POST,
			new HttpEntity<>(Map.of("id", id), english), String.class));
		assertThat(removed.path("msg").asText())
			.isEqualTo("Deleted goal \"English goal\". Events already reported are not affected.");
	}

	// ==================== 权限和租户隔离 ====================

	/** 没登录 401；没有权限 403，查看和管理各拦各的 */
	@Test
	void loginAndPermissionAreRequired() {
		assertThat(conversions(null, APP, "&days=7").getStatusCode().value()).as("没登录应该是 401").isEqualTo(401);
		assertThat(submit(null, eventGoal(APP, "没登录", "some_event")).getStatusCode().value()).isEqualTo(401);
		assertThat(remove(null, "1").getStatusCode().value()).isEqualTo(401);

		assertThat(conversions(lowToken, APP, "&days=7").getStatusCode().value())
			.as("没有 " + TrackConstants.PERM_GOAL_LIST + " 权限应该是 403").isEqualTo(403);
		assertThat(submit(lowToken, eventGoal(APP, "没权限", "some_event")).getStatusCode().value())
			.as("没有 " + TrackConstants.PERM_GOAL_EDIT + " 权限应该是 403").isEqualTo(403);
		assertThat(remove(lowToken, "1").getStatusCode().value()).isEqualTo(403);

		assertThat(ok(conversions(adminToken, APP, "&days=7")).path("goals").size())
			.as("被拦下的请求没有留下目标").isEqualTo(6);
	}

	/** 各租户只看得到、改得了自己应用的目标；目标的归属租户跟着应用走；不同应用的目标可以同名 */
	@Test
	void tenantsOnlySeeAndChangeTheirOwnGoals() {
		String payId = goalByName(ok(conversions(adminToken, APP, "&days=7")), "支付成功").path("id").asText();

		// 另一个租户碰不到平台应用：查不了、加不了，按编号也改不了、删不了
		assertRejected(conversions(tenantBToken, APP, "&days=7"), "应用不存在或无权访问");
		assertRejected(submit(tenantBToken, eventGoal(APP, "别的租户加的", "some_event")), "应用不存在或无权访问");
		Map<String, Object> hijack = new HashMap<>();
		hijack.put("id", payId);
		hijack.put("goalName", "被别的租户改了");
		assertRejected(submit(tenantBToken, hijack), "这个目标不存在");
		assertRejected(remove(tenantBToken, payId), "这个目标不存在");
		JsonNode untouched = goalByName(ok(conversions(adminToken, APP, "&days=7")), "支付成功");
		assertThat(untouched.path("id").asText()).as("平台的目标还在，没被改动").isEqualTo(payId);
		assertThat(untouched.path("uv").asLong()).isEqualTo(2L);

		// 另一个租户在自己的应用里建同名目标：可以，只算自己的 1 个访客
		created(submit(tenantBToken, eventGoal(APP_B, "支付成功", "pay_success")));
		JsonNode own = ok(conversions(tenantBToken, APP_B, "&days=7"));
		assertThat(own.path("uv").asLong()).isEqualTo(1L);
		assertThat(rows(own)).containsExactly("支付成功 uv=1 count=1 rate=1.0");
		String ownId = goalByName(own, "支付成功").path("id").asText();

		// 反过来也一样：平台账号没切到那个租户时，碰不到它的应用和目标
		assertRejected(conversions(adminToken, APP_B, "&days=7"), "应用不存在或无权访问");
		assertRejected(remove(adminToken, ownId), "这个目标不存在");

		// 目标的归属租户取应用的归属租户，操作人记下来了
		Row platformGoal = trackRow("SELECT tenant_id, create_by FROM track_goal WHERE id = ?", Long.parseLong(payId));
		assertThat(platformGoal.getString("tenant_id")).isEqualTo(PLATFORM_TENANT);
		assertThat(platformGoal.getLong("create_by")).as("记下了创建人").isNotNull();
		Row tenantGoal = trackRow("SELECT tenant_id FROM track_goal WHERE id = ?", Long.parseLong(ownId));
		assertThat(tenantGoal.getString("tenant_id")).isEqualTo(tenantBCode);
	}

	// ---------- 测试工具 ----------

	/**
	 * 查一次转化。
	 *
	 * @param extra 追加在后面的查询串，如 {@code &days=7}
	 */
	private ResponseEntity<String> conversions(String token, String appKey, String extra) {
		return rest.exchange("/system/track/goal/conversions?appKey={appKey}" + extra, HttpMethod.GET,
			new HttpEntity<>(authHeaders(token)), String.class, appKey);
	}

	private ResponseEntity<String> submit(String token, Map<String, Object> body) {
		return post("/system/track/goal/submit", body, token);
	}

	private ResponseEntity<String> remove(String token, String id) {
		return post("/system/track/goal/remove", Map.of("id", id), token);
	}

	private static Map<String, Object> eventGoal(String appKey, String name, String eventName) {
		return goal(appKey, name, "event", eventName, null);
	}

	private static Map<String, Object> pageGoal(String appKey, String name, String pagePath) {
		return goal(appKey, name, "page", null, pagePath);
	}

	/** 一份新增目标的请求体；值是 null 的字段不放进去 */
	private static Map<String, Object> goal(String appKey, String name, String type, String eventName, String pagePath) {
		Map<String, Object> body = new HashMap<>();
		body.put("appKey", appKey);
		body.put("goalName", name);
		if (type != null) {
			body.put("goalType", type);
		}
		if (eventName != null) {
			body.put("eventName", eventName);
		}
		if (pagePath != null) {
			body.put("pagePath", pagePath);
		}
		return body;
	}

	/** 保存应该成功，并且回了一句「已保存目标」 */
	private void created(ResponseEntity<String> resp) {
		JsonNode json = readBody(resp);
		assertThat(json.path("code").asInt()).as("保存应该成功：" + json).isEqualTo(200);
		assertThat(json.path("msg").asText()).startsWith("已保存目标「");
	}

	/** 把每个目标写成一句，方便按顺序整个比对 */
	private static List<String> rows(JsonNode data) {
		List<String> list = new ArrayList<>();
		for (JsonNode item : data.path("goals")) {
			list.add(item.path("goalName").asText() + " uv=" + item.path("uv").asLong()
				+ " count=" + item.path("count").asLong() + " rate=" + item.path("rate").asDouble());
		}
		return list;
	}

	private static JsonNode goalByName(JsonNode data, String name) {
		for (JsonNode item : data.path("goals")) {
			if (name.equals(item.path("goalName").asText())) {
				return item;
			}
		}
		throw new IllegalStateException("返回里没有找到目标：" + name + "，收到 " + data);
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
			IdUtil.getSnowflakeNextId(), appKey, "目标转化测试-" + appKey, tenantId));
	}

	/** 一条事件：访客标识就是 {@code visitor}，一个访客一次会话 */
	private TrackIngestEvent event(String appKey, String tenantId, String visitor, String name, long ms,
								   String routePath, String urlPath) {
		TrackIngestEvent e = new TrackIngestEvent();
		e.setEventId(UUID.randomUUID().toString());
		e.setAppKey(appKey);
		e.setEventName(name);
		e.setClientTsMs(ms);
		e.setTsMs(ms);
		e.setReceivedAtMs(ms);
		e.setClockSkewed(0);
		e.setDistinctId(visitor);
		e.setSessionId("s-" + visitor);
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
		role.put("roleName", "目标转化零权限角色");
		role.put("roleCode", ROLE_CODE);
		role.put("sort", 99);
		role.put("dataScope", 1);
		JsonNode roleResp = readBody(post("/system/role/submit", role, adminToken));
		assertThat(roleResp.path("code").asInt()).as("建角色：" + roleResp.path("msg").asText()).isEqualTo(200);

		Map<String, Object> user = new HashMap<>();
		user.put("username", LOW_USERNAME);
		user.put("nickname", "目标转化低权用户");
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
