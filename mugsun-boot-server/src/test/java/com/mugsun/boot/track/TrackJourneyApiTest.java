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
 * 访问路径分析接口（{@code GET /system/track/journey}）的集成测试。
 *
 * <p>测试数据是平台租户一个应用下的 7 次会话，页面按先后排成（括号里是特意加进去的干扰）：
 * <pre>
 * s1  /home → /list →（一条没有页面地址的浏览）→ /detail → /cart
 * s2  /home →（一个自定义事件，地址 /zzz）→ /list → /detail（只有原始路径，没有路由模板）
 * s3  /home → /list → /home → /list → /pay
 * s4  /home → /home（刷新）→ /about
 * s5  /list → /home → /list → /detail
 * s6  /home
 * s7  /search → /list
 * </pre>
 * 按这组数据逐项核对精确数值：
 * <ul>
 *   <li>还没选起点：任意位置按「访问过这个页面的会话数」，入口页按「从这个页面进来的会话数」；</li>
 *   <li>选了步骤：每一步的会话数、下一步的去向、没有下一步的会话数；</li>
 *   <li>步骤要紧挨着（/home 之后隔了一页才到 /detail 的不算）；</li>
 *   <li>连着重复的同一个页面只算一次（s4）、不是页面浏览的事件和没有地址的浏览不算（s1、s2）；</li>
 *   <li>每次会话只从起点页面第一次出现的地方往后看（s3）；把某个去向选成下一步，这一步的会话数就是那一项的数；</li>
 *   <li>去向条数有上限时，列出来的 + 没有下一步的 + 排不进列表的 = 最后一步的会话数；</li>
 *   <li>统计范围外的数据不算；入参不对返回 400 并说明怎么改；英文请求返回英文提示；</li>
 *   <li>没登录 401、没有权限 403、查别的租户的应用 400、各租户只看得到自己的数据。</li>
 * </ul>
 * 事件都放在两天前（相对现在计算，不写死日期）：近 7 天看得到，只看今天看不到。
 * 用到的应用标识是本测试专用的，测完按应用标识删掉事件和应用。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrackJourneyApiTest extends AbstractTrackIntegrationTest {

	private static final String APP = "ak_test_journey_01";
	private static final String APP_B = "ak_test_journey_b";
	private static final String LOW_USERNAME = "it_journey_lowpriv";
	private static final String LOW_PASSWORD = NEW_USER_PASSWORD;
	private static final String ROLE_CODE = "it-journey-noperm";

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
		tenantBody.put("tenantName", "访问路径测试租户");
		tenantBody.put("contactUser", "访问路径测试");
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
		// s1：/list 和 /detail 之间夹一条没有页面地址的浏览，它不占位置，两页仍然算紧挨着
		events.add(view("it-jny-s1", t0, HOME));
		events.add(view("it-jny-s1", t0 + MIN_MS, LIST));
		events.add(event(APP, PLATFORM_TENANT, "it-jny-s1", TrackConstants.EVENT_PAGEVIEW, t0 + 2 * MIN_MS, "", ""));
		events.add(view("it-jny-s1", t0 + 3 * MIN_MS, DETAIL));
		events.add(view("it-jny-s1", t0 + 4 * MIN_MS, "/cart"));
		// s2：中间的自定义事件不是页面浏览，不进路径；最后一页没有路由模板，用原始路径当页面
		events.add(view("it-jny-s2", t0, HOME));
		events.add(event(APP, PLATFORM_TENANT, "it-jny-s2", "add_cart", t0 + MIN_MS, "/zzz", "/zzz"));
		events.add(view("it-jny-s2", t0 + 2 * MIN_MS, LIST));
		events.add(event(APP, PLATFORM_TENANT, "it-jny-s2", TrackConstants.EVENT_PAGEVIEW, t0 + 3 * MIN_MS, "", DETAIL));
		// s3：/home → /list 出现两遍。只从第一次到起点的地方往后看，所以去向是 /home，不是 /pay
		events.add(view("it-jny-s3", t0, HOME));
		events.add(view("it-jny-s3", t0 + MIN_MS, LIST));
		events.add(view("it-jny-s3", t0 + 2 * MIN_MS, HOME));
		events.add(view("it-jny-s3", t0 + 3 * MIN_MS, LIST));
		events.add(view("it-jny-s3", t0 + 4 * MIN_MS, "/pay"));
		// s4：连着两次 /home（刷新）只算一次，去向是 /about
		events.add(view("it-jny-s4", t0, HOME));
		events.add(view("it-jny-s4", t0 + MIN_MS, HOME));
		events.add(view("it-jny-s4", t0 + 2 * MIN_MS, "/about"));
		// s5：入口页是 /list，/home 在第二个位置
		events.add(view("it-jny-s5", t0, LIST));
		events.add(view("it-jny-s5", t0 + MIN_MS, HOME));
		events.add(view("it-jny-s5", t0 + 2 * MIN_MS, LIST));
		events.add(view("it-jny-s5", t0 + 3 * MIN_MS, DETAIL));
		// s6：只看了一页
		events.add(view("it-jny-s6", t0, HOME));
		// s7：没有到过 /home
		events.add(view("it-jny-s7", t0, "/search"));
		events.add(view("it-jny-s7", t0 + MIN_MS, LIST));
		store.insertEvents(events);

		// 另一个租户的应用：一次会话，页面地址和平台应用有重合（/home），两边的数不能串
		store.insertEvents(List.of(
			event(APP_B, tenantBCode, "it-jny-b1", TrackConstants.EVENT_PAGEVIEW, t0, HOME, HOME),
			event(APP_B, tenantBCode, "it-jny-b1", TrackConstants.EVENT_PAGEVIEW, t0 + MIN_MS, "/b-only", "/b-only")));

		assertThat(trackLong("SELECT count(*) AS c FROM track_event WHERE app_key = ?", APP))
			.as("平台应用的 24 条测试事件都写进去了").isEqualTo(24L);
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

	// ==================== 还没选起点 ====================

	/** 任意位置：按访问过这个页面的会话数排；一样多的按页面地址排；不带天数和起点方式时是近 7 天、任意位置 */
	@Test
	void startCandidatesFromAnywhere() {
		JsonNode data = ok(journey(adminToken, APP, null, ""));
		assertThat(data.path("days").asInt()).as("默认近 7 天").isEqualTo(7);
		assertThat(data.path("start").asText()).as("默认任意位置").isEqualTo(TrackConstants.JOURNEY_START_ANY);
		assertThat(data.path("unit").asText()).isEqualTo("session");
		assertThat(data.path("total").asLong()).as("有页面浏览的会话数").isEqualTo(7L);
		assertThat(data.path("steps").size()).isZero();
		assertThat(pages(data.path("next"))).as("可以当起点的页面（自定义事件的 /zzz 不在里面）").containsExactly(
			"/home=6", "/list=5", "/detail=3", "/about=1", "/cart=1", "/pay=1", "/search=1");
		assertThat(data.path("ended").asLong()).isZero();
		assertThat(data.path("others").asLong()).as("一次会话会算进好几个页面，这里不算排不进列表的").isZero();

		JsonNode top2 = ok(journey(adminToken, APP, null, "&days=7&start=any&limit=2"));
		assertThat(pages(top2.path("next"))).containsExactly("/home=6", "/list=5");
		assertThat(top2.path("others").asLong()).isZero();

		JsonNode fallback = ok(journey(adminToken, APP, null, "&limit=0"));
		assertThat(fallback.path("next").size()).as("条数小于 1 时用默认条数").isEqualTo(7);
	}

	/** 入口页：按从这个页面进来的会话数排；每次会话只有一个入口页，排不进列表的算得出来 */
	@Test
	void startCandidatesFromEntry() {
		JsonNode data = ok(journey(adminToken, APP, null, "&start=entry"));
		assertThat(data.path("start").asText()).isEqualTo(TrackConstants.JOURNEY_START_ENTRY);
		assertThat(data.path("total").asLong()).isEqualTo(7L);
		assertThat(pages(data.path("next"))).containsExactly("/home=5", "/list=1", "/search=1");
		assertThat(data.path("others").asLong()).isZero();

		JsonNode top1 = ok(journey(adminToken, APP, null, "&start=entry&limit=1"));
		assertThat(pages(top1.path("next"))).containsExactly("/home=5");
		assertThat(top1.path("others").asLong()).as("另外两次会话的入口页排不进列表").isEqualTo(2L);
	}

	// ==================== 选了步骤 ====================

	/** 一步：/home 之后的去向。s4 的刷新不算去向，s6 只看了一页算没有下一步 */
	@Test
	void nextAfterOneStep() {
		JsonNode any = ok(journey(adminToken, APP, steps(HOME), "&days=7"));
		assertThat(pages(any.path("steps"))).as("到过 /home 的会话").containsExactly("/home=6");
		assertThat(pages(any.path("next"))).containsExactly("/list=4", "/about=1");
		assertThat(any.path("ended").asLong()).as("s6 到 /home 就结束了").isEqualTo(1L);
		assertThat(any.path("others").asLong()).isZero();
		assertThat(any.path("total").asLong()).isEqualTo(7L);

		JsonNode entry = ok(journey(adminToken, APP, steps(HOME), "&days=7&start=entry"));
		assertThat(pages(entry.path("steps"))).as("从 /home 进来的会话（s5 的入口页是 /list，不算）").containsExactly("/home=5");
		assertThat(pages(entry.path("next"))).containsExactly("/list=3", "/about=1");
		assertThat(entry.path("ended").asLong()).isEqualTo(1L);
	}

	/** 两步、三步：每一步的会话数逐步收窄；同一条路径在 s3 里出现两遍只算第一遍 */
	@Test
	void nextAfterSeveralSteps() {
		JsonNode two = ok(journey(adminToken, APP, steps(HOME, LIST), "&days=7"));
		assertThat(pages(two.path("steps"))).containsExactly("/home=6", "/list=4");
		assertThat(pages(two.path("next"))).as("s3 第一遍的去向是 /home，第二遍的 /pay 不算")
			.containsExactly("/detail=3", "/home=1");
		assertThat(two.path("ended").asLong()).isZero();
		assertThat(two.path("others").asLong()).isZero();

		JsonNode three = ok(journey(adminToken, APP, steps(HOME, LIST, DETAIL), "&days=7"));
		assertThat(pages(three.path("steps"))).containsExactly("/home=6", "/list=4", "/detail=3");
		assertThat(pages(three.path("next"))).containsExactly("/cart=1");
		assertThat(three.path("ended").asLong()).as("s2、s5 到 /detail 就结束了").isEqualTo(2L);

		JsonNode threeFromEntry = ok(journey(adminToken, APP, steps(HOME, LIST, DETAIL), "&days=7&start=entry"));
		assertThat(pages(threeFromEntry.path("steps"))).containsExactly("/home=5", "/list=3", "/detail=2");
		assertThat(pages(threeFromEntry.path("next"))).containsExactly("/cart=1");
		assertThat(threeFromEntry.path("ended").asLong()).isEqualTo(1L);
	}

	/**
	 * 每次会话只从起点页面第一次出现的地方往后看。s3 是 /home → /list → /home → /list → /pay：
	 * 以 /list 为起点时看的是第一次到 /list 的地方，后面跟的是 /home；第二次到 /list 再去 /pay 的那一段不算。
	 */
	@Test
	void eachSessionIsFollowedFromItsFirstVisitOfTheStartPage() {
		JsonNode one = ok(journey(adminToken, APP, steps(LIST), "&days=7"));
		assertThat(pages(one.path("steps"))).containsExactly("/list=5");
		assertThat(pages(one.path("next"))).as("s1、s2 去了 /detail；s3、s5 去了 /home；没有 /pay")
			.containsExactly("/detail=2", "/home=2");
		assertThat(one.path("ended").asLong()).as("s7 到 /list 就结束了").isEqualTo(1L);
		assertThat(one.path("others").asLong()).isZero();

		JsonNode pay = ok(journey(adminToken, APP, steps(LIST, "/pay"), "&days=7"));
		assertThat(pages(pay.path("steps"))).as("s3 第二次到 /list 才去的 /pay，不算")
			.containsExactly("/list=5", "/pay=0");
		assertThat(pay.path("next").size()).isZero();
		assertThat(pay.path("ended").asLong()).isZero();
	}

	/** 把去向里的某一项选成下一步以后，这一步的会话数就是刚才那一项的数，它的去向加起来也是这个数 */
	@Test
	void choosingANextPageKeepsItsSessionCount() {
		for (String start : List.of(HOME, LIST)) {
			JsonNode first = ok(journey(adminToken, APP, steps(start), "&days=7"));
			assertThat(first.path("next").size()).as(start + " 应该有去向").isPositive();
			for (JsonNode item : first.path("next")) {
				String page = item.path("page").asText();
				long expected = item.path("sessions").asLong();
				JsonNode second = ok(journey(adminToken, APP, steps(start, page), "&days=7"));
				assertThat(second.path("steps").path(1).path("sessions").asLong())
					.as(start + " → " + page + " 这一步的会话数").isEqualTo(expected);
				long sum = second.path("ended").asLong() + second.path("others").asLong();
				for (JsonNode next : second.path("next")) {
					sum += next.path("sessions").asLong();
				}
				assertThat(sum).as(start + " → " + page + " 的去向加起来").isEqualTo(expected);
			}
		}
	}

	/** 步骤要紧挨着：/home 之后隔了 /list 才到 /detail，不算 /home → /detail */
	@Test
	void stepsMustBeAdjacent() {
		JsonNode data = ok(journey(adminToken, APP, steps(HOME, DETAIL), "&days=7"));
		assertThat(pages(data.path("steps"))).containsExactly("/home=6", "/detail=0");
		assertThat(data.path("next").size()).isZero();
		assertThat(data.path("ended").asLong()).isZero();
		assertThat(data.path("others").asLong()).isZero();
	}

	/** 去向条数有上限：没有下一步的不占条数；列出来的 + 没有下一步的 + 排不进列表的 = 最后一步的会话数 */
	@Test
	void limitKeepsTheNumbersAddingUp() {
		JsonNode one = ok(journey(adminToken, APP, steps(HOME), "&days=7&limit=1"));
		assertThat(pages(one.path("next"))).containsExactly("/list=4");
		assertThat(one.path("ended").asLong()).isEqualTo(1L);
		assertThat(one.path("others").asLong()).as("/about 的 1 次会话排不进列表").isEqualTo(1L);

		JsonNode two = ok(journey(adminToken, APP, steps(HOME, LIST), "&days=7&limit=1"));
		assertThat(pages(two.path("next"))).containsExactly("/detail=3");
		assertThat(two.path("ended").asLong()).isZero();
		assertThat(two.path("others").asLong()).as("/home 的 1 次会话排不进列表").isEqualTo(1L);
	}

	/** 只看今天：两天前的数据不在范围里，各项都是 0，不报错 */
	@Test
	void olderEventsAreOutsideTheWindow() {
		JsonNode data = ok(journey(adminToken, APP, steps(HOME), "&days=1"));
		assertThat(data.path("days").asInt()).isEqualTo(1);
		assertThat(data.path("total").asLong()).isZero();
		assertThat(pages(data.path("steps"))).containsExactly("/home=0");
		assertThat(data.path("next").size()).isZero();
		assertThat(data.path("ended").asLong()).isZero();
		assertThat(data.path("others").asLong()).isZero();

		JsonNode candidates = ok(journey(adminToken, APP, null, "&days=1&start=entry"));
		assertThat(candidates.path("total").asLong()).isZero();
		assertThat(candidates.path("next").size()).isZero();
		assertThat(candidates.path("others").asLong()).isZero();
	}

	// ==================== 入参校验 ====================

	/** 入参不对：返回 400，提示里说明哪里不对、怎么改 */
	@Test
	void invalidParamsAreRejected() {
		assertRejected(get("/system/track/journey", adminToken), "请先选择应用");
		assertRejected(journey(adminToken, "ak_test_journey_none", null, ""), "这个应用不存在");

		assertRejected(journey(adminToken, APP, "/home", ""), "路径步骤的格式不对");
		assertRejected(journey(adminToken, APP, "{}", ""), "路径步骤的格式不对");
		assertRejected(journey(adminToken, APP, steps("/a", "/b", "/c", "/d", "/e", "/f"), ""), "路径最多选 5 步");
		assertRejected(journey(adminToken, APP, "[1]", ""), "路径里的页面地址不对");
		assertRejected(journey(adminToken, APP, "[\" \"]", ""), "路径里的页面地址不对");
		assertRejected(journey(adminToken, APP, steps("/" + "a".repeat(TrackConstants.DIM_MAX_LEN)), ""),
			"路径里的页面地址不对");
		assertRejected(journey(adminToken, APP, steps(HOME, HOME), ""), "相邻两步不能是同一个页面");

		assertRejected(journey(adminToken, APP, null, "&start=first"), "起点方式只支持任意位置和入口页");
		assertRejected(journey(adminToken, APP, null, "&days=0"), "统计天数只支持 1 到 30 天");
		assertRejected(journey(adminToken, APP, null, "&days=" + (TrackConstants.JOURNEY_DAYS_MAX + 1)),
			"统计天数只支持 1 到 30 天");

		JsonNode maxDays = ok(journey(adminToken, APP, steps(HOME), "&days=" + TrackConstants.JOURNEY_DAYS_MAX));
		assertThat(pages(maxDays.path("steps"))).as("最大天数可以查").containsExactly("/home=6");
		JsonNode maxSteps = ok(journey(adminToken, APP, steps(HOME, LIST, HOME, LIST, "/pay"), ""));
		assertThat(pages(maxSteps.path("steps"))).as("最多 5 步可以查，整条路径只有 s3 走完")
			.containsExactly("/home=6", "/list=4", "/home=1", "/list=1", "/pay=1");
		assertThat(maxSteps.path("ended").asLong()).isEqualTo(1L);
	}

	/** 英文请求：提示语是英文，带数字的句子单复数正确 */
	@Test
	void messagesFollowTheRequestLanguage() {
		HttpHeaders english = authHeaders(adminToken);
		english.set(HttpHeaders.ACCEPT_LANGUAGE, "en");
		String url = "/system/track/journey?appKey={appKey}&steps={steps}";

		JsonNode same = readBody(rest.exchange(url, HttpMethod.GET, new HttpEntity<>(english), String.class,
			APP, steps(HOME, HOME)));
		assertThat(same.path("code").asInt()).isEqualTo(400);
		assertThat(same.path("msg").asText())
			.isEqualTo("Two adjacent steps cannot be the same page. Please choose the next step again.");

		JsonNode tooMany = readBody(rest.exchange(url, HttpMethod.GET, new HttpEntity<>(english), String.class,
			APP, steps("/a", "/b", "/c", "/d", "/e", "/f")));
		assertThat(tooMany.path("code").asInt()).isEqualTo(400);
		assertThat(tooMany.path("msg").asText())
			.isEqualTo("A path can have at most 5 steps. Please remove the later steps and check again.");
	}

	// ==================== 权限和租户隔离 ====================

	/** 没登录 401；没有权限 403；查别的租户的应用 400；各租户只看得到自己的数据 */
	@Test
	void accessControlAndTenantIsolation() {
		assertThat(journey(null, APP, steps(HOME), "&days=7").getStatusCode().value())
			.as("没登录应该是 401").isEqualTo(401);
		assertThat(journey(lowToken, APP, steps(HOME), "&days=7").getStatusCode().value())
			.as("没有 " + TrackConstants.PERM_JOURNEY_LIST + " 权限应该是 403").isEqualTo(403);

		assertRejected(journey(tenantBToken, APP, steps(HOME), "&days=7"), "应用不存在或无权访问");

		JsonNode own = ok(journey(tenantBToken, APP_B, steps(HOME), "&days=7"));
		assertThat(own.path("total").asLong()).as("只算本租户的 1 次会话，平台应用的 7 次不串进来").isEqualTo(1L);
		assertThat(pages(own.path("steps"))).containsExactly("/home=1");
		assertThat(pages(own.path("next"))).containsExactly("/b-only=1");
		assertThat(own.path("ended").asLong()).isZero();

		JsonNode ownCandidates = ok(journey(tenantBToken, APP_B, null, "&days=7"));
		assertThat(pages(ownCandidates.path("next"))).containsExactly("/b-only=1", "/home=1");
	}

	// ---------- 测试工具 ----------

	/**
	 * 发一次查询。步骤是 JSON，里面的方括号和引号不能直接拼进地址，所以当成地址模板的变量传，由请求工具编码。
	 *
	 * @param stepsJson 步骤的 JSON，传 null 表示不带这个参数
	 * @param extra     追加在后面的查询串，如 {@code &days=7}，里面不能有花括号
	 */
	private ResponseEntity<String> journey(String token, String appKey, String stepsJson, String extra) {
		HttpEntity<Void> entity = new HttpEntity<>(authHeaders(token));
		if (stepsJson == null) {
			return rest.exchange("/system/track/journey?appKey={appKey}" + extra, HttpMethod.GET, entity,
				String.class, appKey);
		}
		return rest.exchange("/system/track/journey?appKey={appKey}&steps={steps}" + extra, HttpMethod.GET, entity,
			String.class, appKey, stepsJson);
	}

	/** 页面地址拼成 JSON 数组（测试用的地址里没有需要转义的字符） */
	private static String steps(String... pages) {
		return "[\"" + String.join("\",\"", pages) + "\"]";
	}

	/** 把 {@code [{page, sessions}]} 写成 {@code 地址=会话数} 的列表，方便按顺序整个比对 */
	private static List<String> pages(JsonNode array) {
		List<String> list = new ArrayList<>();
		for (JsonNode item : array) {
			list.add(item.path("page").asText() + "=" + item.path("sessions").asLong());
		}
		return list;
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
			IdUtil.getSnowflakeNextId(), appKey, "访问路径测试-" + appKey, tenantId));
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
		role.put("roleName", "访问路径零权限角色");
		role.put("roleCode", ROLE_CODE);
		role.put("sort", 99);
		role.put("dataScope", 1);
		JsonNode roleResp = readBody(post("/system/role/submit", role, adminToken));
		assertThat(roleResp.path("code").asInt()).as("建角色：" + roleResp.path("msg").asText()).isEqualTo(200);

		Map<String, Object> user = new HashMap<>();
		user.put("username", LOW_USERNAME);
		user.put("nickname", "访问路径低权用户");
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
