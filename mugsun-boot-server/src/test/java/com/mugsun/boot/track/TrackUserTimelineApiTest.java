package com.mugsun.boot.track;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.system.service.ParamService;
import com.mugsun.boot.track.job.TrackApiBodyCleanJob;
import com.mugsun.boot.track.job.TrackPartitionJob;
import com.mugsun.boot.track.job.TrackTimelineIndexBackfillJob;
import com.mugsun.boot.track.job.TrackTimelineIndexProperties;
import com.mugsun.boot.track.job.TrackTimelineIndexes;
import com.mugsun.boot.track.job.TrackTimelineIndexes.IndexSpec;
import db.track.migration.T4__track_event_timeline_index_partitions;
import org.springframework.core.env.Environment;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import com.mybatisflex.core.datasource.DataSourceKey;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G102 用户细查 + 接口响应体集成测试：行为时间线（identity 归并/游标分页/distinct 直查/范围硬限/401/403/跨租户）
 * + /track/api-body 上传（gzip/明文/幂等/开关拒收/超限 413）→ 存储真文件 → 读取 round-trip → 清理任务 → 审计留痕。
 * <p>数据准备：四个测试应用（主应用 monitor+body 开 / 全关 / 短保留期 / B 租户）SQL 直灌；
 * 事件走 /track/collect 真实摄入链路（带 token 事件 user_id 裁定见 TrackCollectApiTest ⑥），异步落库一律 await 轮询。
 * 测后清理：track 域行 + api-body 对象文件 + 幂等 Redis 键（应用键统一 it-g102 前缀）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrackUserTimelineApiTest extends AbstractTrackIntegrationTest {

	private static final String APP_T = "it-g102-main";
	private static final String APP_OFF = "it-g102-off";
	private static final String APP_CLEAN = "it-g102-clean";
	private static final String APP_B = "it-g102-b";
	private static final String LOW_USERNAME = "it_g102_lowpriv";
	private static final String LOW_PASSWORD = "123456";
	private static final String ROLE_CODE = "it-g102-noperm";
	/** 本地存储落盘根（基座动态属性 dromara.x-file-storage.local-plus[0].storage-path） */
	private static final String STORAGE_ROOT = "target/it-files/";
	/** 对象键月份段格式（与 TrackApiBodyStorage 同口径，UTC） */
	private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern(TrackConstants.API_BODY_PATH_MONTH_PATTERN);

	/** 响应体原文（round-trip 断言基准） */
	private static final String BODY_JSON = "{\"code\":200,\"data\":{\"list\":[1,2,3],\"name\":\"细查\"},\"msg\":\"ok\"}";

	@Autowired
	private ParamService paramService;
	@Autowired
	private TrackApiBodyCleanJob cleanJob;
	@Autowired
	private TrackPartitionJob partitionJob;
	@Autowired
	private TrackTimelineIndexBackfillJob backfillJob;
	@Autowired
	private TrackTimelineIndexProperties indexProps;
	@Autowired
	private Environment env;
	@Autowired
	private StringRedisTemplate redis;

	private String adminToken;
	private Long adminId;
	private String tenantBCode;
	private String tenantBToken;
	private String lowToken;

	/** 时间线夹具：D1 匿名 2 事件 → identify 绑 admin → D1 登录 1 事件；D2 直接登录 1 事件 */
	private String d1;
	private String d2;
	private String s1;
	private String s2;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		adminId = currentUserId(ADMIN_USERNAME);

		// 第二租户（自动初始化其 admin/123456 管理员，持租户内 * 通配）+ 低权用户（零权限角色）
		Map<String, Object> tenantBody = new HashMap<>();
		tenantBody.put("tenantName", "G102测试租户");
		tenantBody.put("contactUser", "G102测试");
		JsonNode tenantResp = readBody(post("/system/tenant/create", tenantBody, adminToken));
		assertThat(tenantResp.path("code").asInt()).as("建租户：" + tenantResp.path("msg").asText()).isEqualTo(200);
		tenantBCode = tenantResp.path("data").asText();
		tenantBToken = login(tenantBCode, ADMIN_USERNAME, ADMIN_PASSWORD);
		lowToken = createLowPrivilegeUser();

		seedApp(APP_T, PLATFORM_TENANT, 1, 1, 7);
		seedApp(APP_OFF, PLATFORM_TENANT, 0, 0, 7);
		seedApp(APP_CLEAN, PLATFORM_TENANT, 1, 1, 1);
		seedApp(APP_B, tenantBCode, 1, 1, 7);

		// 匿名期（无 token：user_id 落 NULL）
		d1 = uuid();
		s1 = uuid();
		collect(APP_T, List.of(
			event("$pageview", d1, s1, Map.of("url_path", "/u-anon-1")),
			event("$click", d1, s1, Map.of("url_path", "/u-anon-2"))), null);
		// identify 绑定（token 一致才落映射；$identify 事件本身也入时间线）
		collect(APP_T, List.of(identifyEvent(d1, s1, adminId)), adminToken);
		// 登录期（带 token：user_id 裁定为 admin）
		collect(APP_T, List.of(event("$pageview", d1, s1, Map.of("url_path", "/u-home"))), adminToken);
		d2 = uuid();
		s2 = uuid();
		collect(APP_T, List.of(event("$pageview", d2, s2, Map.of("url_path", "/u-d2"))), adminToken);

		awaitUntil("identity 归并映射落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_identity WHERE app_key = ? AND distinct_id = ? AND user_id = ?",
			APP_T, d1, adminId) == 1L);
		awaitUntil("时间线夹具 5 事件落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE app_key = ? AND session_id IN (?, ?)",
			APP_T, s1, s2) == 5L);
	}

	@AfterAll
	void cleanup() throws IOException {
		// api-body 对象文件（测后清理，防 target/it-files 跨用例膨胀）
		for (String appKey : List.of(APP_T, APP_OFF, APP_CLEAN, APP_B)) {
			File dir = new File(STORAGE_ROOT + "mugsun/api-body/" + appKey);
			if (dir.exists()) {
				try (var walk = Files.walk(dir.toPath())) {
					walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
				}
			}
		}
		// track 域行（事件/会话/身份/事件定义/应用）
		DataSourceKey.use(TrackConstants.DS_KEY, () -> {
			Db.updateBySql("DELETE FROM track_event WHERE app_key IN (?, ?, ?, ?)", APP_T, APP_OFF, APP_CLEAN, APP_B);
			Db.updateBySql("DELETE FROM track_session WHERE app_key IN (?, ?, ?, ?)", APP_T, APP_OFF, APP_CLEAN, APP_B);
			Db.updateBySql("DELETE FROM track_identity WHERE app_key IN (?, ?, ?, ?)", APP_T, APP_OFF, APP_CLEAN, APP_B);
			Db.updateBySql("DELETE FROM track_event_def WHERE app_key IN (?, ?, ?, ?)", APP_T, APP_OFF, APP_CLEAN, APP_B);
			Db.updateBySql("DELETE FROM track_app WHERE app_key IN (?, ?, ?, ?)", APP_T, APP_OFF, APP_CLEAN, APP_B);
		});
		// api-body 幂等 Redis 键（TTL 25h，主动清理防跨用例污染）
		Set<String> keys = redis.keys(TrackConstants.API_BODY_IDEMPOTENT_KEY_PREFIX + "*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	// ==================== 行为时间线 ====================

	/** ① timeline 按用户查：identify 后匿名期 distinct_id 行为经 track_identity 归并并上
	 *  （匿名 2 + identify 1 + 登录 D1 1 + 登录 D2 1 = 5；匿名事件库内 user_id 仍为 NULL，纯 JOIN 归并） */
	@Test
	void timelineByUserMergesAnonymousViaIdentity() {
		// 注意：须连非空列一起查——selectOneBySql 对「全列皆 NULL 的行」按无行返回 null（Flex Row 映射特性）
		Row anon = trackRow("SELECT event_name, user_id FROM track_event WHERE app_key = ? AND url_path = '/u-anon-1'", APP_T);
		assertThat(anon).as("匿名期夹具行应存在").isNotNull();
		assertThat(anon.get("user_id")).as("匿名期事件库内 user_id 为 NULL（归并靠 identity JOIN 而非回填）").isNull();

		JsonNode data = readBodyAssertOk(timelineByUser(adminId, null));
		assertThat(data.path("nextCursor").isNull()).as("单页查全（5 < 默认 20）").isTrue();
		assertThat(data.path("devicesTruncated").asBoolean()).as("设备数未超上限").isFalse();
		assertThat(data.path("deviceCount").asInt()).as("admin 映射了 d1 一个匿名设备").isGreaterThanOrEqualTo(1);
		JsonNode records = data.path("records");
		assertThat(records.size()).as("identity 归并后 5 条").isEqualTo(5);
		Set<String> paths = new HashSet<>();
		Set<String> names = new HashSet<>();
		for (JsonNode row : records) {
			paths.add(row.path("urlPath").asText());
			names.add(row.path("eventName").asText());
			assertThat(row.path("ts").asLong()).isPositive();
			assertThat(row.path("clientTs").asLong()).isPositive();
			assertThat(row.path("props").asText()).as("props 为 JSON 原文字符串").startsWith("{");
		}
		assertThat(paths).contains("/u-anon-1", "/u-anon-2", "/u-home", "/u-d2");
		assertThat(names).contains("$pageview", "$click", "$identify");
	}

	/** ② 游标分页稳定性：5 事件两页半，三页不重复不漏（ts+id 游标，防 offset 跳变） */
	@Test
	void cursorPaginationStable() {
		String distinct = uuid();
		String session = uuid();
		List<Map<String, Object>> events = new ArrayList<>();
		Set<String> sentIds = new HashSet<>();
		for (int i = 1; i <= 5; i++) {
			Map<String, Object> e = event("$pageview", distinct, session, Map.of("url_path", "/c" + i));
			events.add(e);
			sentIds.add((String) e.get("event_id"));
		}
		// 同批摄入（ts 可能同毫秒）：游标 (ts, id) 仍唯一，分页不乱
		collect(APP_T, events, null);
		awaitUntil("游标用例 5 事件落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE app_key = ? AND distinct_id = ?", APP_T, distinct) == 5L);

		Set<String> seen = new HashSet<>();
		String cursor = null;
		int pages = 0;
		long rangeStart = System.currentTimeMillis() - 3600000;
		long rangeEnd = System.currentTimeMillis() + 60000;
		do {
			String url = "/system/track/user/timeline?appKey=" + APP_T + "&distinctId=" + distinct
				+ "&startTs=" + rangeStart + "&endTs=" + rangeEnd + "&pageSize=2"
				+ (cursor == null ? "" : "&cursor=" + cursor);
			JsonNode data = readBodyAssertOk(get(url, adminToken));
			pages++;
			for (JsonNode row : data.path("records")) {
				String eventId = row.path("eventId").asText();
				assertThat(seen.add(eventId)).as("跨页不重复: " + eventId).isTrue();
			}
			cursor = data.path("nextCursor").isNull() ? null : data.path("nextCursor").asText();
		} while (cursor != null && pages <= 5);
		assertThat(pages).as("5 条 pageSize=2 应 3 页").isEqualTo(3);
		assertThat(seen).as("三页不漏（全量事件 id 覆盖）").isEqualTo(sentIds);
	}

	/** ②b 按显示的发生时间 ts 倒序：先收到的事件 ts 更晚、后收到的 ts 更早，同 ts 两条按 id 定序；翻页仍单调 */
	@Test
	void timelineOrderedByTsNotReceivedAt() {
		String distinct = uuid();
		String session = uuid();
		long base = System.currentTimeMillis() - 60000;
		// 第一批（先收到）：ts 最晚的两条
		Map<String, Object> late1 = event("$click", distinct, session, Map.of("url_path", "/late-1"));
		late1.put("ts", base + 3000);
		Map<String, Object> late2 = event("$click", distinct, session, Map.of("url_path", "/late-2"));
		late2.put("ts", base + 2000);
		collect(APP_T, List.of(late1, late2), null);
		awaitUntil("第一批落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE app_key = ? AND distinct_id = ?", APP_T, distinct) == 2L);
		// 第二批（后收到）：ts 更早，其中两条同 ts
		Map<String, Object> early1 = event("$pageview", distinct, session, Map.of("url_path", "/early-1"));
		early1.put("ts", base + 1000);
		Map<String, Object> early2 = event("$pageview", distinct, session, Map.of("url_path", "/early-2"));
		early2.put("ts", base + 1000);
		Map<String, Object> earliest = event("$pageview", distinct, session, Map.of("url_path", "/earliest"));
		earliest.put("ts", base);
		collect(APP_T, List.of(early1, early2, earliest), null);
		awaitUntil("第二批落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE app_key = ? AND distinct_id = ?", APP_T, distinct) == 5L);

		List<Long> tsSeq = new ArrayList<>();
		List<String> pathSeq = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		long rangeStart = System.currentTimeMillis() - 3600000;
		long rangeEnd = System.currentTimeMillis() + 60000;
		do {
			String url = "/system/track/user/timeline?appKey=" + APP_T + "&distinctId=" + distinct
				+ "&startTs=" + rangeStart + "&endTs=" + rangeEnd + "&pageSize=2"
				+ (cursor == null ? "" : "&cursor=" + cursor);
			JsonNode data = readBodyAssertOk(get(url, adminToken));
			pages++;
			for (JsonNode row : data.path("records")) {
				tsSeq.add(row.path("ts").asLong());
				pathSeq.add(row.path("urlPath").asText());
			}
			cursor = data.path("nextCursor").isNull() ? null : data.path("nextCursor").asText();
		} while (cursor != null && pages <= 5);

		assertThat(pathSeq).as("5 条全出且不重复").hasSize(5).doesNotHaveDuplicates();
		assertThat(tsSeq).as("跨页按显示时间 ts 严格非增").isSortedAccordingTo(java.util.Comparator.reverseOrder());
		assertThat(pathSeq.subList(0, 2)).as("先收到但 ts 晚的排前面").containsExactly("/late-1", "/late-2");
		assertThat(pathSeq.subList(2, 4)).as("同 ts 两条相邻").containsExactlyInAnyOrder("/early-1", "/early-2");
		assertThat(pathSeq.get(4)).isEqualTo("/earliest");
	}

	/** ③ distinct_id 直查：只出该匿名 ID 的行为（不带 identity 归并他 ID） */
	@Test
	void timelineByDistinctId() {
		JsonNode data = readBodyAssertOk(timelineByDistinct(d1, null));
		assertThat(data.path("records").size()).as("D1 口径 4 条（匿名 2 + identify + 登录 1）").isEqualTo(4);
		Set<String> paths = new HashSet<>();
		for (JsonNode row : data.path("records")) {
			paths.add(row.path("urlPath").asText());
		}
		assertThat(paths).contains("/u-anon-1", "/u-anon-2", "/u-home");
		assertThat(paths).as("D2 行为不混入").doesNotContain("/u-d2");

		JsonNode d2Data = readBodyAssertOk(timelineByDistinct(d2, null));
		assertThat(d2Data.path("records").size()).as("D2 口径 1 条").isEqualTo(1);
	}

	/** ④ 范围硬限：超 7 天 → 400；缺 userId/distinctId → 400 */
	@Test
	void rangeOverSevenDaysRejected() {
		long now = System.currentTimeMillis();
		JsonNode over = readBody(get("/system/track/user/timeline?appKey=" + APP_T + "&userId=" + adminId
			+ "&startTs=" + (now - 8L * 86400000) + "&endTs=" + now, adminToken));
		assertThat(over.path("code").asInt()).as("范围超 7 天应 400").isEqualTo(400);
		assertThat(over.path("msg").asText()).contains("7 天");

		JsonNode noTarget = readBody(get("/system/track/user/timeline?appKey=" + APP_T
			+ "&startTs=" + (now - 3600000) + "&endTs=" + now, adminToken));
		assertThat(noTarget.path("code").asInt()).as("缺 userId/distinctId 应 400").isEqualTo(400);
	}

	/** ⑤ 访问控制：无 token 401；低权 403（timeline 与 api-body 双端点）；跨租户查不到（空页/400） */
	@Test
	void accessControl() {
		long now = System.currentTimeMillis();
		String tlUrl = "/system/track/user/timeline?appKey=" + APP_T + "&userId=" + adminId
			+ "&startTs=" + (now - 3600000) + "&endTs=" + now;
		assertThat(get(tlUrl, null).getStatusCode().value()).as("无 token timeline 应 401").isEqualTo(401);
		assertThat(get(tlUrl, lowToken).getStatusCode().value()).as("低权 timeline 应 403").isEqualTo(403);

		JsonNode cross = readBodyAssertOk(get(tlUrl, tenantBToken));
		assertThat(cross.path("records").size()).as("跨租户 timeline 空页（tenant 条件过滤）").isEqualTo(0);

		// api-body 端点同口径（先用主应用造一条 body）
		String eventId = uuid();
		pushApiRequest(APP_T, eventId, uuid(), uuid());
		assertThat(postApiBodyGzip(APP_T, eventId, BODY_JSON).getStatusCode().value()).isEqualTo(200);
		String bodyUrl = "/system/track/user/api-body?eventId=" + eventId;
		assertThat(get(bodyUrl, null).getStatusCode().value()).as("无 token api-body 应 401").isEqualTo(401);
		assertThat(get(bodyUrl, lowToken).getStatusCode().value()).as("低权 api-body 应 403").isEqualTo(403);
		JsonNode crossBody = readBody(get(bodyUrl, tenantBToken));
		assertThat(crossBody.path("code").asInt()).as("跨租户 api-body 应 400（未采集或已清理）").isEqualTo(400);
	}

	// ==================== 接口响应体上传 / 读取 ====================

	/** ⑥ api-body 上传（gzip）→ 存储真文件存在 → 读取 round-trip 一致（服务端解压明文，application/json 非信封）；
	 *  时间线行 hasApiBody=true（props 含 body_ref）、hasReplay 经 track_session 标记 */
	@Test
	void apiBodyRoundTrip() throws IOException {
		String eventId = uuid();
		String distinct = uuid();
		String session = uuid();
		pushApiRequest(APP_T, eventId, distinct, session);
		awaitUntil("会话落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_session WHERE session_id = ?", session) == 1L);
		// 会话回放标记直置（时间线 hasReplay 经 track_session 左联读取）
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(
			"UPDATE track_session SET has_replay = 1 WHERE session_id = ?", session));

		ResponseEntity<String> resp = postApiBodyGzip(APP_T, eventId, BODY_JSON);
		assertThat(resp.getStatusCode().value()).as("合法 body 上传：" + resp.getBody()).isEqualTo(200);
		JsonNode data = readBody(resp).path("data");
		assertThat(data.path("accepted").asBoolean()).isTrue();
		assertThat(data.path("duplicated").asBoolean()).isFalse();

		// 存储真文件存在且为 gzip 字节（魔数 1f8b）
		File stored = bodyFile(APP_T, eventId);
		assertThat(stored).as("存储文件应真实存在: " + stored.getAbsolutePath()).exists();
		byte[] bytes = Files.readAllBytes(stored.toPath());
		assertThat(bytes[0] & 0xff).as("落储字节应为 gzip（魔数 1f）").isEqualTo(0x1f);
		assertThat(bytes[1] & 0xff).as("落储字节应为 gzip（魔数 8b）").isEqualTo(0x8b);

		// 读取：服务端解压直发明文，与上传原文一致
		ResponseEntity<String> read = get("/system/track/user/api-body?eventId=" + eventId, adminToken);
		assertThat(read.getStatusCode().value()).isEqualTo(200);
		assertThat(read.getHeaders().getContentType().toString()).contains("application/json");
		assertThat(read.getBody()).as("读取与上传原文一致（非 R 信封）").isEqualTo(BODY_JSON);

		// 时间线行：hasApiBody=true、hasReplay=1、接口元数据成列
		JsonNode tl = readBodyAssertOk(timelineByDistinct(distinct, null));
		JsonNode row = null;
		for (JsonNode r : tl.path("records")) {
			if (eventId.equals(r.path("eventId").asText())) {
				row = r;
			}
		}
		assertThat(row).as("时间线应含 api_request 行").isNotNull();
		assertThat(row.path("eventName").asText()).isEqualTo("api_request");
		assertThat(row.path("urlPath").asText()).isEqualTo("/system/user/page");
		assertThat(row.path("durationMs").asInt()).isEqualTo(42);
		assertThat(row.path("hasApiBody").asBoolean()).as("props 含 body_ref 即 true").isTrue();
		assertThat(row.path("hasReplay").asInt()).as("hasReplay 经 track_session 标记").isEqualTo(1);
		assertThat(row.path("props").asText()).contains(TrackConstants.PROP_BODY_REF);
	}

	/** ⑦ api_body_enabled=0 拒收（400）；/track/config 下发三开关与 apiBodyMaxBytes（读 sys_param） */
	@Test
	void bodyDisabledAppRejectedAndConfig() {
		ResponseEntity<String> resp = postApiBodyGzip(APP_OFF, uuid(), BODY_JSON);
		assertThat(resp.getStatusCode().value()).as("body 采集关闭应用应拒收").isEqualTo(400);
		assertThat(readBody(resp).path("msg").asText()).contains("没有打开响应体采集");
		assertThat(bodyFile(APP_OFF, "never")).as("拒收不落对象").doesNotExist();

		JsonNode on = readBody(get("/track/config?app_key=" + APP_T, null)).path("data");
		assertThat(on.path("apiMonitorEnabled").asBoolean()).isTrue();
		assertThat(on.path("apiBodyEnabled").asBoolean()).isTrue();
		assertThat(on.path("apiBodyMaskEnabled").asBoolean()).as("脱敏默认关").isFalse();
		assertThat(on.path("apiBodyMaxBytes").asLong())
			.as("apiBodyMaxBytes 读 sys_param 默认 1MB").isEqualTo(TrackConstants.DEFAULT_API_BODY_MAX_BYTES);

		JsonNode off = readBody(get("/track/config?app_key=" + APP_OFF, null)).path("data");
		assertThat(off.path("apiMonitorEnabled").asBoolean()).isFalse();
		assertThat(off.path("apiBodyEnabled").asBoolean()).isFalse();
	}

	/** ⑧ 超 sys_param 上限 413（测试调小参数）：gzip 体按解压后口径拦，明文体按解码长度拦 */
	@Test
	void oversizedBodyRejected413() {
		paramService.setValue(TrackConstants.PARAM_API_BODY_MAX_BYTES, "100");
		try {
			String big = "{\"data\":\"" + "x".repeat(500) + "\"}";
			ResponseEntity<String> gz = postApiBodyGzip(APP_T, uuid(), big);
			assertThat(gz.getStatusCode().value()).as("解压后超 100 应 413").isEqualTo(413);
			assertThat(readBody(gz).path("code").asInt()).isEqualTo(413);

			ResponseEntity<String> plain = postApiBodyPlain(APP_T, uuid(), big);
			assertThat(plain.getStatusCode().value()).as("明文超 100 应 413").isEqualTo(413);
		} finally {
			paramService.setValue(TrackConstants.PARAM_API_BODY_MAX_BYTES,
				String.valueOf(TrackConstants.DEFAULT_API_BODY_MAX_BYTES));
		}
	}

	/** ⑨ 幂等重发：同 event_id 二次上传 200 duplicated=true；gzip=false 明文体服务端补压后读取还原一致 */
	@Test
	void duplicateBodyDroppedIdempotent() {
		String eventId = uuid();
		pushApiRequest(APP_T, eventId, uuid(), uuid());
		ResponseEntity<String> first = postApiBodyPlain(APP_T, eventId, BODY_JSON);
		assertThat(first.getStatusCode().value()).as("明文体（gzip:false）应收：" + first.getBody()).isEqualTo(200);
		assertThat(readBody(first).path("data").path("duplicated").asBoolean()).isFalse();

		ResponseEntity<String> second = postApiBodyPlain(APP_T, eventId, BODY_JSON);
		assertThat(second.getStatusCode().value()).isEqualTo(200);
		assertThat(readBody(second).path("data").path("duplicated").asBoolean())
			.as("同 event_id 重发应幂等命中").isTrue();

		// 明文体服务端补压落储：文件魔数 1f8b；读取还原与原文一致
		assertThat(bodyFile(APP_T, eventId)).exists();
		ResponseEntity<String> read = get("/system/track/user/api-body?eventId=" + eventId, adminToken);
		assertThat(read.getStatusCode().value()).isEqualTo(200);
		assertThat(read.getBody()).as("明文体补压后读取还原一致").isEqualTo(BODY_JSON);
	}

	/** ⑩ 清理任务：到期（received_at 回拨超保留期）对象删除；未到期对象保留。
	 *  到期对象按「回拨月份」手工落盘（模拟 2 天前上传的对象：键月份按事件 received_at 推导的设计约束，
	 *  月初边界无脆弱性）；未到期对象走真实上传链路 */
	@Test
	void cleanJobPurgesExpired() throws IOException {
		String expiredEvent = uuid();
		pushApiRequest(APP_CLEAN, expiredEvent, uuid(), uuid());

		String aliveEvent = uuid();
		pushApiRequest(APP_CLEAN, aliveEvent, uuid(), uuid());
		assertThat(postApiBodyGzip(APP_CLEAN, aliveEvent, BODY_JSON).getStatusCode().value()).isEqualTo(200);
		File aliveFile = bodyFile(APP_CLEAN, aliveEvent);
		assertThat(aliveFile).exists();

		// 构造到期：received_at 回拨 2 天 > 应用保留期 1 天；对象落回拨月份目录（与清理键推导同口径）
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(
			"UPDATE track_event SET received_at = now() - interval '2 days' WHERE event_id = ?", expiredEvent));
		String expiredMonth = MONTH_FORMAT.format(Instant.now().minus(java.time.Duration.ofDays(2)).atOffset(ZoneOffset.UTC));
		File expiredFile = new File(STORAGE_ROOT + "mugsun/api-body/" + APP_CLEAN + "/" + expiredMonth + "/"
			+ expiredEvent + TrackConstants.API_BODY_FILE_SUFFIX);
		Files.createDirectories(expiredFile.getParentFile().toPath());
		Files.write(expiredFile.toPath(), gzip(BODY_JSON.getBytes(StandardCharsets.UTF_8)));
		assertThat(expiredFile).exists();

		String summary = cleanJob.cleanNow();
		assertThat(summary).contains("接口响应体保留期清理");
		assertThat(expiredFile).as("到期对象应物理删除").doesNotExist();
		assertThat(aliveFile).as("未到期对象保留").exists();

		// 事件行不动：body 过期后读取诚实 400（清单留存，清理幂等可重入）
		JsonNode read = readBody(get("/system/track/user/api-body?eventId=" + expiredEvent, adminToken));
		assertThat(read.path("code").asInt()).as("body 已清理应 400").isEqualTo(400);
		assertThat(read.path("msg").asText()).contains("没有采到，或已经清理");
	}

	/** T2 迁移：时间线两条 (app_key, 身份, ts, id) 复合索引已建在分区父表，且列序与游标排序一致 */
	@Test
	void timelineIndexesMigrated() {
		Row user = trackRow("SELECT indexdef FROM pg_indexes WHERE tablename = 'track_event' AND indexname = ?",
			"idx_event_user_ts_timeline");
		assertThat(user).as("idx_event_user_ts_timeline 应由 T3 建出").isNotNull();
		assertThat(user.getString("indexdef")).contains("ON ONLY").contains("(app_key, user_id, ts, id)");
		Row distinct = trackRow("SELECT indexdef FROM pg_indexes WHERE tablename = 'track_event' AND indexname = ?",
			"idx_event_distinct_ts_timeline");
		assertThat(distinct).as("idx_event_distinct_ts_timeline 应由 T3 建出").isNotNull();
		assertThat(distinct.getString("indexdef")).contains("ON ONLY").contains("(app_key, distinct_id, ts, id)");
		assertThat(trackLong("SELECT count(*) AS c FROM flyway_schema_history WHERE version IN ('3', '4') AND success"))
			.as("T3 + T4 迁移成功").isEqualTo(2L);
		assertTimelineIndexesValid();
	}

	/** ⑩b 新分区（TrackPartitionJob 预建）自动带上时间线索引并已挂接、valid */
	@Test
	void newPartitionGetsTimelineIndexes() {
		YearMonth far = YearMonth.now(ZoneOffset.UTC).plusMonths(7);
		String part = String.format("track_event_%04d_%02d", far.getYear(), far.getMonthValue());
		String dataPart = String.format("track_event_data_%04d_%02d", far.getYear(), far.getMonthValue());
		partitionJob.ensureMonthPartitions(far);
		try {
			for (String idx : List.of("idx_event_user_ts_timeline", "idx_event_distinct_ts_timeline")) {
				assertThat(trackLong("SELECT count(*) AS c FROM pg_inherits i JOIN pg_index x ON x.indexrelid = i.inhrelid"
					+ " JOIN pg_class t ON t.oid = x.indrelid WHERE i.inhparent = ?::regclass AND t.relname = ? AND x.indisvalid",
					idx, part)).as(part + " 应带 " + idx).isEqualTo(1L);
			}
			assertTimelineIndexesValid();
		} finally {
			DataSourceKey.use(TrackConstants.DS_KEY, () -> {
				Db.updateBySql("DROP TABLE IF EXISTS \"" + part + "\"");
				Db.updateBySql("DROP TABLE IF EXISTS \"" + dataPart + "\"");
			});
		}
	}

	/** ⑩c userId 路径 UNION ALL 改写与原跨表 OR 口径逐行一致（小页翻全量，顺序/去重均比对） */
	@Test
	void userTimelineUnionMatchesOrSemantics() {
		long now = System.currentTimeMillis();
		long start = now - 3600000;
		long end = now + 60000;
		List<String> expected = new ArrayList<>();
		DataSourceKey.use(TrackConstants.DS_KEY, () -> {
			for (Row r : Db.selectListBySql("SELECT e.event_id FROM track_event e LEFT JOIN track_identity m"
					+ " ON m.app_key = e.app_key AND m.distinct_id = e.distinct_id AND m.is_deleted = 0"
					+ " WHERE e.app_key = ? AND (e.user_id = ? OR m.user_id = ?)"
					+ " AND e.received_at >= to_timestamp(? / 1000.0) AND e.received_at <= to_timestamp(? / 1000.0)"
					+ " ORDER BY e.ts DESC, e.id DESC",
				APP_T, adminId, adminId, start, end)) {
				expected.add(r.getString("event_id"));
			}
		});
		assertThat(expected).as("夹具含登录段与匿名归并段").hasSizeGreaterThanOrEqualTo(5);
		List<String> actual = new ArrayList<>();
		String cursor = null;
		for (int guard = 0; guard < 50; guard++) {
			JsonNode data = readBodyAssertOk(get("/system/track/user/timeline?appKey=" + APP_T + "&userId=" + adminId
				+ "&startTs=" + start + "&endTs=" + end + "&pageSize=2"
				+ (cursor == null ? "" : "&cursor=" + cursor), adminToken));
			for (JsonNode row : data.path("records")) {
				actual.add(row.path("eventId").asText());
			}
			if (data.path("nextCursor").isNull()) {
				break;
			}
			cursor = data.path("nextCursor").asText();
		}
		assertThat(actual).as("UNION ALL 分页结果与 OR 口径逐行一致（无重复无遗漏、同序）").containsExactlyElementsOf(expected);
	}

	/** ⑩d P1：T4 只处理空分区、非空分区留给后台任务且不抛异常；后台补建按分区记进度、校验 valid；锁/开关/时段 */
	@Test
	void t4SkipsNonEmptyAndBackfillCompletes() throws Exception {
		List<IndexSpec> specs = List.of(new IndexSpec("idx_event_tltest_tl", "app_key, session_id, ts, id"));
		YearMonth cur = YearMonth.now(ZoneOffset.UTC);
		String curPart = String.format("track_event_%04d_%02d", cur.getYear(), cur.getMonthValue());
		YearMonth next = cur.plusMonths(1);
		String nextPart = String.format("track_event_%04d_%02d", next.getYear(), next.getMonthValue());
		assertThat(trackLong("SELECT count(*) AS c FROM " + curPart)).as("当月分区有夹具数据").isPositive();
		boolean enabled = indexProps.isBackfillEnabled();
		boolean window = indexProps.isWindowEnabled();
		try (Connection conn = trackConnection(); Connection other = trackConnection()) {
			exec(conn, "CREATE INDEX idx_event_tltest_tl ON ONLY track_event (app_key, session_id, ts, id)");
			boolean nextEmpty = TrackTimelineIndexes.isEmpty(conn, nextPart);

			// T4：非空分区跳过（不同步建），空分区建好挂上；不抛异常
			List<String> pending = T4__track_event_timeline_index_partitions.run(conn, specs);
			assertThat(pending).contains(curPart);
			assertThat(TrackTimelineIndexes.partitionReady(conn, curPart, specs)).isFalse();
			if (nextEmpty) {
				assertThat(pending).doesNotContain(nextPart);
				assertThat(TrackTimelineIndexes.partitionReady(conn, nextPart, specs)).isTrue();
			}
			assertThat(TrackTimelineIndexes.indexValid(conn, "idx_event_tltest_tl")).as("未挂齐父索引仍 invalid").isFalse();

			// 别的会话持锁：T4 跳过不抛、后台任务 LOCK_BUSY
			exec(other, "SELECT pg_advisory_lock(hashtext('track_timeline_index'))");
			assertThat(T4__track_event_timeline_index_partitions.run(conn, specs)).isEmpty();
			indexProps.setBackfillEnabled(true);
			indexProps.setWindowEnabled(false);
			assertThat(backfillJob.runNow(specs).status()).isEqualTo(TrackTimelineIndexBackfillJob.Status.LOCK_BUSY);
			exec(other, "SELECT pg_advisory_unlock(hashtext('track_timeline_index'))");

			// 开关关闭 / 时段外
			indexProps.setBackfillEnabled(false);
			assertThat(backfillJob.runNow(specs).status()).isEqualTo(TrackTimelineIndexBackfillJob.Status.DISABLED);
			indexProps.setBackfillEnabled(true);
			indexProps.setWindowEnabled(true);
			backfillJob.setClock(Clock.fixed(Instant.parse("2026-01-01T04:00:00Z"), ZoneOffset.UTC)); // 北京 12:00
			assertThat(backfillJob.runNow(specs).status()).isEqualTo(TrackTimelineIndexBackfillJob.Status.OUTSIDE_WINDOW);
			backfillJob.setClock(Clock.fixed(Instant.parse("2026-01-01T19:30:00Z"), ZoneOffset.UTC)); // 北京 03:30

			// 后台补建：补齐非空分区、父索引转 valid、按分区记进度
			TrackTimelineIndexBackfillJob.Result r = backfillJob.runNow(specs);
			assertThat(r.status()).isEqualTo(TrackTimelineIndexBackfillJob.Status.DONE);
			assertThat(r.built()).contains(curPart);
			assertThat(TrackTimelineIndexes.allValid(conn, specs)).isTrue();
			assertThat(trackLong("SELECT count(*) AS c FROM track_rollup_cursor WHERE job_key = 'timeline_index' AND app_key = ?",
				curPart)).as("分区进度已记录").isEqualTo(1L);
			assertThat(backfillJob.runNow(specs).status()).isEqualTo(TrackTimelineIndexBackfillJob.Status.NOTHING_TO_DO);
		} finally {
			indexProps.setBackfillEnabled(enabled);
			indexProps.setWindowEnabled(window);
			backfillJob.setClock(Clock.systemUTC());
			DataSourceKey.use(TrackConstants.DS_KEY, () -> {
				Db.updateBySql("DROP INDEX IF EXISTS idx_event_tltest_tl");
				Db.updateBySql("DELETE FROM track_rollup_cursor WHERE job_key = 'timeline_index'");
			});
		}
	}

	/** ⑩d2 R-1：关停 = 停止标志 + 中断停顿 + 不再开始下一个分区；恢复后下次运行补齐（含中断留下的 invalid 索引） */
	@Test
	void backfillStopsOnShutdownAndResumes() throws Exception {
		List<IndexSpec> specs = List.of(new IndexSpec("idx_event_tlstop_tl", "app_key, session_id, ts, id"));
		boolean enabled = indexProps.isBackfillEnabled();
		boolean window = indexProps.isWindowEnabled();
		long pause = indexProps.getPauseMillis();
		try (Connection conn = trackConnection()) {
			exec(conn, "CREATE INDEX idx_event_tlstop_tl ON ONLY track_event (app_key, session_id, ts, id)");
			int parts = TrackTimelineIndexes.partitions(conn).size();
			assertThat(parts).as("至少两个分区才能验证『不开始下一个』").isGreaterThanOrEqualTo(2);
			indexProps.setBackfillEnabled(true);
			indexProps.setWindowEnabled(false);
			indexProps.setPauseMillis(60_000);
			java.util.concurrent.atomic.AtomicReference<TrackTimelineIndexBackfillJob.Result> result =
				new java.util.concurrent.atomic.AtomicReference<>();
			Thread worker = new Thread(() -> result.set(backfillJob.runNow(specs)));
			worker.start();
			// 第一个分区建完进入 60s 停顿后关停
			awaitUntil("第一个分区补完", () -> trackLong(
				"SELECT count(*) AS c FROM track_rollup_cursor WHERE job_key = 'timeline_index'") >= 1L);
			Thread.sleep(300);
			backfillJob.shutdown();
			worker.interrupt(); // 测试线程代替执行器线程：shutdownNow 中断的是执行器线程
			worker.join(10_000);
			assertThat(worker.isAlive()).as("关停后应立即退出，不等 60s 停顿").isFalse();
			assertThat(result.get().status()).isEqualTo(TrackTimelineIndexBackfillJob.Status.STOPPED);
			assertThat(result.get().built()).hasSize(1);
			assertThat(TrackTimelineIndexes.allValid(conn, specs)).as("未开始后续分区").isFalse();
			assertThat(backfillJob.runNow(specs).status()).as("停止期间不再运行").isEqualTo(TrackTimelineIndexBackfillJob.Status.STOPPED);

			// 恢复：下次运行补齐剩余分区
			backfillJob.resetForTest();
			indexProps.setPauseMillis(0);
			assertThat(backfillJob.runNow(specs).status()).isEqualTo(TrackTimelineIndexBackfillJob.Status.DONE);
			assertThat(TrackTimelineIndexes.allValid(conn, specs)).isTrue();
		} finally {
			backfillJob.resetForTest();
			indexProps.setBackfillEnabled(enabled);
			indexProps.setWindowEnabled(window);
			indexProps.setPauseMillis(pause);
			DataSourceKey.use(TrackConstants.DS_KEY, () -> {
				Db.updateBySql("DROP INDEX IF EXISTS idx_event_tlstop_tl");
				Db.updateBySql("DELETE FROM track_rollup_cursor WHERE job_key = 'timeline_index'");
			});
		}
	}

	/** ⑩d3 后台补建会话 statement_timeout=0：连接默认 200ms 超时下照样跑完（结束后 RESET） */
	@Test
	void backfillIgnoresSessionStatementTimeout() throws Exception {
		List<IndexSpec> specs = List.of(new IndexSpec("idx_event_tlto_tl", "app_key, session_id, ts, id"));
		boolean enabled = indexProps.isBackfillEnabled();
		boolean window = indexProps.isWindowEnabled();
		String db = env.getProperty("mybatis-flex.datasource.track.url").replaceAll("^.*/([^/?]+)(\\?.*)?$", "$1");
		try (Connection conn = trackConnection()) {
			exec(conn, "CREATE INDEX idx_event_tlto_tl ON ONLY track_event (app_key, session_id, ts, id)");
			// 库级默认超时 200ms：只影响之后新建的连接，所以给任务一个新连接来跑（直接用 runNow 走连接池可能复用老连接，
			// 这里改用独立连接 + 同一实现路径验证 SET 生效）
			exec(conn, "ALTER DATABASE \"" + db + "\" SET statement_timeout = '200ms'");
			try (Connection slow = trackConnection()) {
				indexProps.setBackfillEnabled(true);
				indexProps.setWindowEnabled(false);
				TrackTimelineIndexBackfillJob.Result r = backfillJob.runOn(slow, specs);
				assertThat(r.status()).isEqualTo(TrackTimelineIndexBackfillJob.Status.DONE);
				try (Statement st = slow.createStatement(); var rs = st.executeQuery("SHOW statement_timeout")) {
					rs.next();
					assertThat(rs.getString(1)).as("结束后 RESET 回连接默认值").isEqualTo("200ms");
				}
			} finally {
				exec(conn, "ALTER DATABASE \"" + db + "\" RESET statement_timeout");
			}
		} finally {
			indexProps.setBackfillEnabled(enabled);
			indexProps.setWindowEnabled(window);
			DataSourceKey.use(TrackConstants.DS_KEY, () -> {
				Db.updateBySql("DROP INDEX IF EXISTS idx_event_tlto_tl");
				Db.updateBySql("DELETE FROM track_rollup_cursor WHERE job_key = 'timeline_index'");
			});
		}
	}

	/** ⑩e ATTACH 前校验 indisvalid：中断留下的 invalid 子索引先 DROP 再重建，挂上后 valid */
	@Test
	void invalidChildRebuiltBeforeAttach() throws Exception {
		YearMonth far = YearMonth.of(2099, 1);
		String part = "track_event_2099_01";
		List<IndexSpec> specs = List.of(new IndexSpec("idx_event_tlinv_tl", "app_key, session_id, ts, id"));
		partitionJob.ensureMonthPartitions(far);
		try (Connection conn = trackConnection()) {
			exec(conn, "CREATE INDEX idx_event_tlinv_tl ON ONLY track_event (app_key, session_id, ts, id)");
			String child = part + "_tlinv_tl";
			exec(conn, "CREATE INDEX " + child + " ON " + part + " (app_key, session_id, ts, id)");
			exec(conn, "UPDATE pg_index SET indisvalid = false WHERE indexrelid = '" + child + "'::regclass");
			assertThat(TrackTimelineIndexes.indexValid(conn, child)).isFalse();
			TrackTimelineIndexes.ensurePartition(conn, part, specs, false);
			assertThat(TrackTimelineIndexes.partitionReady(conn, part, specs)).as("重建后 valid 并已挂接").isTrue();
		} finally {
			DataSourceKey.use(TrackConstants.DS_KEY, () -> {
				Db.updateBySql("DROP INDEX IF EXISTS idx_event_tlinv_tl");
				Db.updateBySql("DROP TABLE IF EXISTS track_event_2099_01");
				Db.updateBySql("DROP TABLE IF EXISTS track_event_data_2099_01");
			});
		}
	}

	/** ⑩f userId 匿名段设备数封顶 1000（按 last_seen_time 新→旧）：最近设备的事件可见，第 1001 个最旧设备不参与 */
	@Test
	void mappedDevicesCappedAt1000() {
		long uid = 880_000_001L;
		String prefix = "cap-" + uuid().substring(0, 8) + "-";
		DataSourceKey.use(TrackConstants.DS_KEY, () -> {
			Db.updateBySql("INSERT INTO track_identity (id, app_key, distinct_id, user_id, tenant_id, first_bind_time, last_seen_time)"
				+ " SELECT 9100000000 + g, ?, ? || g, ?, ?, now(), now() - g * interval '1 second' FROM generate_series(0, 1000) g",
				APP_T, prefix, uid, PLATFORM_TENANT);
			Db.updateBySql("INSERT INTO track_event (id, event_id, app_key, event_name, client_ts, ts, received_at, distinct_id,"
				+ " session_id, tenant_id, url_path) SELECT 9200000000 + g, gen_random_uuid()::text, ?, '$pageview', now(), now(), now(),"
				+ " ? || g, ?, ?, '/cap-' || g FROM unnest(ARRAY[0, 1000]) g", APP_T, prefix, uuid(), PLATFORM_TENANT);
		});
		try {
			JsonNode data = readBodyAssertOk(timelineByUser(uid, null));
			Set<String> paths = new HashSet<>();
			data.path("records").forEach(r -> paths.add(r.path("urlPath").asText()));
			assertThat(paths).contains("/cap-0").doesNotContain("/cap-1000");
			assertThat(data.path("devicesTruncated").asBoolean()).as("超 1000 设备须显式标记截断").isTrue();
			assertThat(data.path("deviceCount").asInt()).isEqualTo(1000);
		} finally {
			DataSourceKey.use(TrackConstants.DS_KEY, () -> {
				Db.updateBySql("DELETE FROM track_event WHERE app_key = ? AND distinct_id LIKE ?", APP_T, prefix + "%");
				Db.updateBySql("DELETE FROM track_identity WHERE app_key = ? AND distinct_id LIKE ?", APP_T, prefix + "%");
			});
		}
	}

	private Connection trackConnection() throws Exception {
		Connection c = DriverManager.getConnection(env.getProperty("mybatis-flex.datasource.track.url"),
			env.getProperty("mybatis-flex.datasource.track.username"), env.getProperty("mybatis-flex.datasource.track.password"));
		c.setAutoCommit(true);
		return c;
	}

	private static void exec(Connection c, String sql) throws Exception {
		try (Statement st = c.createStatement()) {
			st.execute(sql);
		}
	}

	private void assertTimelineIndexesValid() {
		long parts = trackLong("SELECT count(*) AS c FROM pg_inherits WHERE inhparent = 'track_event'::regclass");
		for (String idx : List.of("idx_event_user_ts_timeline", "idx_event_distinct_ts_timeline")) {
			assertThat(trackLong("SELECT count(*) AS c FROM pg_index WHERE indexrelid = ?::regclass AND indisvalid", idx))
				.as(idx + " 父索引 indisvalid").isEqualTo(1L);
			assertThat(trackLong("SELECT count(*) AS c FROM pg_inherits i JOIN pg_index x ON x.indexrelid = i.inhrelid"
				+ " WHERE i.inhparent = ?::regclass AND x.indisvalid", idx))
				.as(idx + " 每个分区一条 valid 子索引").isEqualTo(parts);
		}
	}

	/** ⑪ body 读取审计留痕：sys_oper_log 写「查看接口响应体」（标题/URI/操作人/参数含事件） */
	@Test
	void bodyViewAuditLogged() {
		String eventId = uuid();
		pushApiRequest(APP_T, eventId, uuid(), uuid());
		assertThat(postApiBodyGzip(APP_T, eventId, BODY_JSON).getStatusCode().value()).isEqualTo(200);
		ResponseEntity<String> read = get("/system/track/user/api-body?eventId=" + eventId, adminToken);
		assertThat(read.getStatusCode().value()).isEqualTo(200);

		// 操作日志异步落库，await 轮询（业务库）
		awaitUntil("查看接口响应体操作日志落库", () -> {
			Row log = Db.selectOneBySql(
				"SELECT count(*) AS c FROM sys_oper_log WHERE title = '查看接口响应体'"
					+ " AND request_uri = '/system/track/user/api-body' AND operator = ? AND params LIKE ?",
				String.valueOf(adminId), "%" + eventId + "%");
			return log.getLong("c") >= 1L;
		});
	}

	// ---------- 测试工具 ----------

	/** 测试应用直灌（api_monitor/api_body/保留期三列一并落；ON CONFLICT 幂等保配置） */
	private void seedApp(String appKey, String tenantId, int apiMonitor, int apiBody, int bodyRetentionDays) {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(
			"INSERT INTO track_app (id, app_key, app_name, platform, tenant_id, sample_rate, enabled,"
				+ " api_monitor_enabled, api_body_enabled, api_body_mask_enabled, api_body_retention_days,"
				+ " create_time, update_time, is_deleted)"
				+ " VALUES (?, ?, ?, 'web', ?, 100, 1, ?, ?, 0, ?, now(), now(), 0)"
				+ " ON CONFLICT (app_key) WHERE is_deleted = 0 DO UPDATE SET tenant_id = EXCLUDED.tenant_id,"
				+ " enabled = 1, api_monitor_enabled = EXCLUDED.api_monitor_enabled,"
				+ " api_body_enabled = EXCLUDED.api_body_enabled,"
				+ " api_body_retention_days = EXCLUDED.api_body_retention_days",
			IdUtil.getSnowflakeNextId(), appKey, "G102测试应用-" + appKey, tenantId, apiMonitor, apiBody, bodyRetentionDays));
	}

	/** 按用户查时间线（近 1 小时窗口） */
	private ResponseEntity<String> timelineByUser(Long userId, String cursor) {
		long now = System.currentTimeMillis();
		return get("/system/track/user/timeline?appKey=" + APP_T + "&userId=" + userId
			+ "&startTs=" + (now - 3600000) + "&endTs=" + (now + 60000)
			+ (cursor == null ? "" : "&cursor=" + cursor), adminToken);
	}

	/** 按 distinct_id 查时间线（近 1 小时窗口） */
	private ResponseEntity<String> timelineByDistinct(String distinctId, String cursor) {
		long now = System.currentTimeMillis();
		return get("/system/track/user/timeline?appKey=" + APP_T + "&distinctId=" + distinctId
			+ "&startTs=" + (now - 3600000) + "&endTs=" + (now + 60000)
			+ (cursor == null ? "" : "&cursor=" + cursor), adminToken);
	}

	/** 经真实摄入链路推一条 api_request 事件（props.body_ref = 自身 event_id）并等落库 */
	private void pushApiRequest(String appKey, String eventId, String distinctId, String sessionId) {
		Map<String, Object> props = new HashMap<>();
		props.put(TrackConstants.PROP_BODY_REF, eventId);
		props.put("url_path", "/system/user/page");
		props.put("method", "GET");
		props.put("status", 200);
		props.put("duration_ms", 42);
		Map<String, Object> e = event("api_request", distinctId, sessionId, props);
		e.put("event_id", eventId);
		collect(appKey, List.of(e), null);
		awaitUntil("api_request 事件落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE event_id = ?", eventId) == 1L);
	}

	/** 上传 gzip 响应体（协议：{app_key, event_id, gzip:true, payload:base64(gzip(原文))}） */
	private ResponseEntity<String> postApiBodyGzip(String appKey, String eventId, String content) {
		Map<String, Object> body = new HashMap<>();
		body.put("app_key", appKey);
		body.put("event_id", eventId);
		body.put("gzip", true);
		body.put("payload", Base64.getEncoder().encodeToString(gzip(content.getBytes(StandardCharsets.UTF_8))));
		return post("/track/api-body", body, null);
	}

	/** 上传明文响应体（gzip:false = base64(明文)，SDK 收尾场景协议） */
	private ResponseEntity<String> postApiBodyPlain(String appKey, String eventId, String content) {
		Map<String, Object> body = new HashMap<>();
		body.put("app_key", appKey);
		body.put("event_id", eventId);
		body.put("gzip", false);
		body.put("payload", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
		return post("/track/api-body", body, null);
	}

	/** 本地落盘文件（键 api-body/{app_key}/{yyyyMM}/{event_id}.json.gz，yyyyMM 取上传月 UTC） */
	private File bodyFile(String appKey, String eventId) {
		String month = MONTH_FORMAT.format(Instant.now().atOffset(ZoneOffset.UTC));
		return new File(STORAGE_ROOT + "mugsun/api-body/" + appKey + "/" + month + "/"
			+ eventId + TrackConstants.API_BODY_FILE_SUFFIX);
	}

	/** 经 /track/collect 真实摄入一批事件（token 可空；空=匿名，非空=user_id 裁定） */
	private void collect(String appKey, List<Map<String, Object>> events, String token) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("app_key", appKey);
		payload.put("schema_version", 1);
		payload.put("sdk", Map.of("platform", "web", "version", "0.0.1-it"));
		payload.put("sent_at", System.currentTimeMillis());
		payload.put("events", events);
		ResponseEntity<String> resp = post("/track/collect", payload, token);
		assertThat(resp.getStatusCode().value()).as("摄入应受理：" + resp.getBody()).isEqualTo(200);
	}

	/** 事件载体（ts=当前毫秒） */
	private Map<String, Object> event(String name, String distinctId, String sessionId, Map<String, Object> props) {
		Map<String, Object> e = new HashMap<>();
		e.put("event_id", uuid());
		e.put("event", name);
		e.put("ts", System.currentTimeMillis());
		e.put("distinct_id", distinctId);
		e.put("session_id", sessionId);
		e.put("props", props);
		return e;
	}

	/** $identify 事件（props.user_id = 声称绑定用户，与 token 一致才落映射） */
	private Map<String, Object> identifyEvent(String distinctId, String sessionId, Long claimedUserId) {
		return event("$identify", distinctId, sessionId, Map.of("user_id", String.valueOf(claimedUserId)));
	}

	private JsonNode readBodyAssertOk(ResponseEntity<String> resp) {
		JsonNode json = readBody(resp);
		assertThat(json.path("code").asInt()).as("请求应成功：" + json).isEqualTo(200);
		return json.path("data");
	}

	/** 建零权限角色 + 低权用户并授权（TrackAlertSourcemapApiTest 同款），返回其 token */
	private String createLowPrivilegeUser() {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "G102零权限角色");
		role.put("roleCode", ROLE_CODE);
		role.put("sort", 99);
		role.put("dataScope", 1);
		JsonNode roleResp = readBody(post("/system/role/submit", role, adminToken));
		assertThat(roleResp.path("code").asInt()).as("建角色：" + roleResp.path("msg").asText()).isEqualTo(200);

		Map<String, Object> user = new HashMap<>();
		user.put("username", LOW_USERNAME);
		user.put("nickname", "G102低权用户");
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

	/** 业务库查用户 id（按用户名 + 平台租户收窄） */
	private Long currentUserId(String username) {
		Row row = Db.selectOneBySql("SELECT id FROM sys_user WHERE username = ? AND tenant_id = ? AND is_deleted = 0",
			username, PLATFORM_TENANT);
		assertThat(row).as("用户应存在: " + username).isNotNull();
		return row.getLong("id");
	}

	private byte[] gzip(byte[] raw) {
		try (ByteArrayOutputStream out = new ByteArrayOutputStream();
			 GZIPOutputStream gzip = new GZIPOutputStream(out)) {
			gzip.write(raw);
			gzip.finish();
			return out.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException("测试 gzip 压缩失败", e);
		}
	}

	private String uuid() {
		return UUID.randomUUID().toString();
	}
}
