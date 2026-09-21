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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G101 错误监控增强集成测试：sourcemap 管理 API（上传校验链/分页/删除/raw 原文 + 401/403/跨租户）
 * + 错误告警引擎（规则 A 新指纹首告去重 / 规则 B 频次窗阈值与窗级抑制 / 开关关闭不触发 / 告警信内容字段）。
 * <p>数据准备：三个测试应用（sourcemap 专用 / 告警开阈值 3 / 告警关）SQL 直灌；
 * $error 走 /track/collect 真实摄入链路，告警站内信异步落库一律 await 轮询断言。
 * 测后清理：track 域行 + sourcemap 对象文件 + 告警 Redis 键 + 告警站内信行（指纹统一含 itg101 标记）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrackAlertSourcemapApiTest extends AbstractTrackIntegrationTest {

	private static final String APP_SM = "it-g101-sm";
	private static final String APP_ALERT = "it-g101-alert";
	private static final String APP_OFF = "it-g101-off";
	private static final int ALERT_THRESHOLD = 3;
	private static final String LOW_USERNAME = "it-g101-lowpriv";
	private static final String LOW_PASSWORD = "123456";
	private static final String ROLE_CODE = "it-g101-noperm";
	/** 本地存储落盘根（基座动态属性 dromara.x-file-storage.local-plus[0].storage-path） */
	private static final String STORAGE_ROOT = "target/it-files/";

	/** 规则 A 用指纹（含 itg101 清理标记） */
	private static final String FP_NEW = "itg101-fp-new";
	/** 规则 B 用指纹 */
	private static final String FP_TH = "itg101-fp-th";
	/** 告警关闭应用用指纹 */
	private static final String FP_OFF = "itg101-fp-off";
	/** 内容字段断言用指纹 */
	private static final String FP_CONTENT = "itg101-fp-content";

	/** 合法 .map 内容（sourcemap v3 必要字段齐备） */
	private static final String MAP_JSON = "{\"version\":3,\"file\":\"app.js\",\"mappings\":\"AAAA\","
		+ "\"sources\":[\"app.ts\"],\"sourcesContent\":[\"const a = 1;\"]}";

	@Autowired
	private StringRedisTemplate redis;

	private String adminToken;
	private Long adminId;
	private String tenantBCode;
	private String tenantBToken;
	private String lowToken;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		adminId = currentUserId(ADMIN_USERNAME);

		// 第二租户（自动初始化其 admin/123456 管理员，持租户内 * 通配）+ 低权用户（零权限角色）
		Map<String, Object> tenantBody = new HashMap<>();
		tenantBody.put("tenantName", "G101测试租户");
		tenantBody.put("contactUser", "G101测试");
		JsonNode tenantResp = readBody(post("/system/tenant/create", tenantBody, adminToken));
		assertThat(tenantResp.path("code").asInt()).as("建租户：" + tenantResp.path("msg").asText()).isEqualTo(200);
		tenantBCode = tenantResp.path("data").asText();
		tenantBToken = login(tenantBCode, ADMIN_USERNAME, ADMIN_PASSWORD);
		lowToken = createLowPrivilegeUser();

		seedApp(APP_SM, PLATFORM_TENANT, 0, TrackConstants.DEFAULT_ALERT_THRESHOLD);
		seedApp(APP_ALERT, PLATFORM_TENANT, 1, ALERT_THRESHOLD);
		seedApp(APP_OFF, PLATFORM_TENANT, 0, TrackConstants.DEFAULT_ALERT_THRESHOLD);
	}

	@AfterAll
	void cleanup() throws IOException {
		// sourcemap 对象文件（测后清理，防 target/it-files 跨用例膨胀）
		File dir = new File(STORAGE_ROOT + "mugsun/sourcemap/" + APP_SM);
		if (dir.exists()) {
			try (var walk = Files.walk(dir.toPath())) {
				walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
			}
		}
		// track 域行（元数据/事件/会话/事件定义/应用）
		DataSourceKey.use(TrackConstants.DS_KEY, () -> {
			Db.updateBySql("DELETE FROM track_sourcemap WHERE app_key = ?", APP_SM);
			Db.updateBySql("DELETE FROM track_event WHERE app_key IN (?, ?, ?)", APP_SM, APP_ALERT, APP_OFF);
			Db.updateBySql("DELETE FROM track_session WHERE app_key IN (?, ?, ?)", APP_SM, APP_ALERT, APP_OFF);
			Db.updateBySql("DELETE FROM track_event_def WHERE app_key IN (?, ?, ?)", APP_SM, APP_ALERT, APP_OFF);
			Db.updateBySql("DELETE FROM track_app WHERE app_key IN (?, ?, ?)", APP_SM, APP_ALERT, APP_OFF);
		});
		// 告警 Redis 键（alert-new/alert-freq/alert-sent 三前缀一网打尽）
		Set<String> keys = redis.keys(TrackConstants.REDIS_PREFIX + "alert-*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
		// 告警站内信行（先收件人后主体；指纹含 itg101 标记收窄）
		Db.updateBySql("DELETE FROM sys_message_user WHERE message_id IN"
			+ " (SELECT id FROM sys_message WHERE title = ? AND content LIKE '%itg101%')", TrackConstants.ALERT_MESSAGE_TITLE);
		Db.updateBySql("DELETE FROM sys_message WHERE title = ? AND content LIKE '%itg101%'",
			TrackConstants.ALERT_MESSAGE_TITLE);
	}

	// ==================== sourcemap 管理 API ====================

	/** ① 合法 .map 上传 → 元数据行（坐标/体积/租户/操作人）+ 存储真文件字节一致；同键重传 = 覆盖更新不累行 */
	@Test
	void uploadLegalMapPersistsRowAndFile() throws IOException {
		byte[] mapBytes = MAP_JSON.getBytes(StandardCharsets.UTF_8);
		JsonNode data = readBodyAssertOk(uploadSourcemap(adminToken, mapBytes, "app.js.map", APP_SM, "1.0.0"));
		long id = data.path("id").asLong();
		assertThat(data.path("appKey").asText()).isEqualTo(APP_SM);
		assertThat(data.path("release").asText()).isEqualTo("1.0.0");
		assertThat(data.path("filename").asText()).isEqualTo("app.js.map");
		assertThat(data.path("sizeBytes").asLong()).isEqualTo(mapBytes.length);
		assertThat(data.has("storageKey")).as("响应不下发存储坐标").isFalse();

		Row row = trackRow("SELECT app_key, release, filename, size_bytes, tenant_id, create_by, storage_key,"
			+ " storage_platform FROM track_sourcemap WHERE id = ? AND is_deleted = 0", id);
		assertThat(row).as("元数据行应落库").isNotNull();
		assertThat(row.getString("tenant_id")).as("tenant_id 服务端裁定").isEqualTo(PLATFORM_TENANT);
		assertThat(row.getLong("create_by")).as("create_by 落上传操作人").isEqualTo(adminId);
		assertThat(row.getLong("size_bytes")).isEqualTo(mapBytes.length);
		String storageKey = row.getString("storage_key");
		assertThat(storageKey).as("对象键规则 sourcemap/{app_key}/{release}/{filename}")
			.isEqualTo("mugsun/sourcemap/" + APP_SM + "/1.0.0/app.js.map");
		assertThat(row.getString("storage_platform")).isNotBlank();

		// 存储真文件存在且字节与上传一致
		File stored = new File(STORAGE_ROOT + storageKey);
		assertThat(stored).as("存储文件应真实存在: " + stored.getAbsolutePath()).exists();
		assertThat(Files.readAllBytes(stored.toPath())).as("读写字节一致").isEqualTo(mapBytes);

		// 同 (app_key, release, filename) 重传 = 覆盖：仍一行，体积/内容刷新
		byte[] v2 = MAP_JSON.replace("const a = 1;", "const a = 2;").getBytes(StandardCharsets.UTF_8);
		JsonNode reup = readBodyAssertOk(uploadSourcemap(adminToken, v2, "app.js.map", APP_SM, "1.0.0"));
		assertThat(reup.path("id").asLong()).as("同键重传更新同一行").isEqualTo(id);
		assertThat(trackLong("SELECT count(*) AS c FROM track_sourcemap WHERE app_key = ? AND release = '1.0.0'"
			+ " AND is_deleted = 0", APP_SM)).as("重传不累行").isEqualTo(1L);
		assertThat(Files.readAllBytes(stored.toPath())).as("对象已同键覆写").isEqualTo(v2);
	}

	/** ② 拒绝链：非 .map 后缀 / 超 20MB / 坏 JSON / 缺 mappings 字段 / 应用不存在，一律 400 且不落行 */
	@Test
	void uploadRejectsInvalid() {
		byte[] mapBytes = MAP_JSON.getBytes(StandardCharsets.UTF_8);
		// 用例无序：以基线差值判定「全部拒绝不落行」
		long baseline = trackLong("SELECT count(*) AS c FROM track_sourcemap WHERE app_key = ? AND is_deleted = 0", APP_SM);
		JsonNode badExt = readBody(uploadSourcemap(adminToken, mapBytes, "app.js.txt", APP_SM, "1.0.0"));
		assertThat(badExt.path("code").asInt()).as("非 .map 应 400").isEqualTo(400);
		assertThat(badExt.path("msg").asText()).contains(".map");

		byte[] oversize = new byte[(int) TrackConstants.SOURCEMAP_MAX_BYTES + 1];
		JsonNode tooBig = readBody(uploadSourcemap(adminToken, oversize, "big.js.map", APP_SM, "1.0.0"));
		assertThat(tooBig.path("code").asInt()).as("超 20MB 应 400").isEqualTo(400);
		assertThat(tooBig.path("msg").asText()).contains("20MB");

		JsonNode badJson = readBody(uploadSourcemap(adminToken,
			"this is not json".getBytes(StandardCharsets.UTF_8), "bad.js.map", APP_SM, "1.0.0"));
		assertThat(badJson.path("code").asInt()).as("坏 JSON 应 400").isEqualTo(400);
		assertThat(badJson.path("msg").asText()).contains("JSON");

		JsonNode noMappings = readBody(uploadSourcemap(adminToken,
			"{\"version\":3}".getBytes(StandardCharsets.UTF_8), "nomap.js.map", APP_SM, "1.0.0"));
		assertThat(noMappings.path("code").asInt()).as("缺 mappings 应 400").isEqualTo(400);
		assertThat(noMappings.path("msg").asText()).contains("mappings");

		JsonNode noApp = readBody(uploadSourcemap(adminToken, mapBytes, "orphan.js.map", "it-g101-no-such", "1.0.0"));
		assertThat(noApp.path("code").asInt()).as("应用不存在应 400").isEqualTo(400);

		JsonNode badRelease = readBody(uploadSourcemap(adminToken, mapBytes, "r.js.map", APP_SM, "../escape"));
		assertThat(badRelease.path("code").asInt()).as("release 路径穿越应 400").isEqualTo(400);

		assertThat(trackLong("SELECT count(*) AS c FROM track_sourcemap WHERE app_key = ? AND is_deleted = 0", APP_SM))
			.as("全部拒绝不落行").isEqualTo(baseline);
	}

	/** ③ page/remove 流程 + 无 token 401 + 低权 403 + 跨租户不可见/不可删；删除后对象与行同清 */
	@Test
	void pageRemoveFlowAndAuthz() throws IOException {
		byte[] mapBytes = MAP_JSON.getBytes(StandardCharsets.UTF_8);
		long idA = readBodyAssertOk(uploadSourcemap(adminToken, mapBytes, "page-a.js.map", APP_SM, "2.0.0"))
			.path("id").asLong();
		readBodyAssertOk(uploadSourcemap(adminToken, mapBytes, "page-b.js.map", APP_SM, "2.0.0"));

		// 分页：两文件可见；记录无存储坐标；release 过滤生效
		JsonNode page = readBodyAssertOk(get("/system/track/sourcemap/page?appKey=" + APP_SM
			+ "&release=2.0.0&pageNum=1&pageSize=50", adminToken));
		long total = page.path("totalRow").asLong();
		assertThat(total).as("release=2.0.0 两条").isEqualTo(2L);
		for (JsonNode record : page.path("records")) {
			assertThat(record.has("storageKey")).as("分页不下发 storage_key").isFalse();
			assertThat(record.has("storagePlatform")).as("分页不下发存储平台").isFalse();
			assertThat(record.has("storageBasePath")).isFalse();
		}
		JsonNode filtered = readBodyAssertOk(get("/system/track/sourcemap/page?appKey=" + APP_SM
			+ "&release=9.9.9", adminToken));
		assertThat(filtered.path("totalRow").asLong()).as("无此 release 应空").isEqualTo(0L);

		// 无 token → 401
		assertThat(get("/system/track/sourcemap/page?appKey=" + APP_SM, null).getStatusCode().value())
			.as("无 token 分页应 401").isEqualTo(401);
		assertThat(uploadSourcemap(null, mapBytes, "noauth.js.map", APP_SM, "2.0.0").getStatusCode().value())
			.as("无 token 上传应 401").isEqualTo(401);
		assertThat(post("/system/track/sourcemap/remove", Map.of("id", idA), null).getStatusCode().value())
			.as("无 token 删除应 401").isEqualTo(401);

		// 低权 → 403
		assertThat(get("/system/track/sourcemap/page?appKey=" + APP_SM, lowToken).getStatusCode().value())
			.as("低权分页应 403").isEqualTo(403);
		assertThat(uploadSourcemap(lowToken, mapBytes, "low.js.map", APP_SM, "2.0.0").getStatusCode().value())
			.as("低权上传应 403").isEqualTo(403);
		assertThat(post("/system/track/sourcemap/remove", Map.of("id", idA), lowToken).getStatusCode().value())
			.as("低权删除应 403").isEqualTo(403);

		// 跨租户：B 租户管理员分页不可见、删除命中「不存在」
		JsonNode cross = readBodyAssertOk(get("/system/track/sourcemap/page?appKey=" + APP_SM
			+ "&pageSize=50", tenantBToken));
		assertThat(cross.path("totalRow").asLong()).as("跨租户不可见").isEqualTo(0L);
		JsonNode crossRemove = readBody(post("/system/track/sourcemap/remove", Map.of("id", idA), tenantBToken));
		assertThat(crossRemove.path("code").asInt()).as("跨租户删除应 400").isEqualTo(400);

		// 删除：对象与行同清，另一文件不受影响
		String keyA = trackRow("SELECT storage_key FROM track_sourcemap WHERE id = ?", idA).getString("storage_key");
		File fileA = new File(STORAGE_ROOT + keyA);
		assertThat(fileA).exists();
		JsonNode removed = readBody(post("/system/track/sourcemap/remove", Map.of("id", idA), adminToken));
		assertThat(removed.path("code").asInt()).isEqualTo(200);
		assertThat(fileA).as("对象已物删").doesNotExist();
		assertThat(trackLong("SELECT count(*) AS c FROM track_sourcemap WHERE id = ? AND is_deleted = 0", idA))
			.as("行已逻辑删").isEqualTo(0L);
		assertThat(trackLong("SELECT count(*) AS c FROM track_sourcemap WHERE app_key = ? AND release = '2.0.0'"
			+ " AND is_deleted = 0", APP_SM)).as("另一文件保留").isEqualTo(1L);
	}

	/** ④ raw 端点：返回 .map 原文（application/json 非信封）；无 token 401、低权 403、跨租户 400 */
	@Test
	void rawReturnsOriginalWithAuthz() {
		byte[] mapBytes = MAP_JSON.getBytes(StandardCharsets.UTF_8);
		long id = readBodyAssertOk(uploadSourcemap(adminToken, mapBytes, "raw.js.map", APP_SM, "3.0.0"))
			.path("id").asLong();

		ResponseEntity<String> raw = get("/system/track/sourcemap/raw?id=" + id, adminToken);
		assertThat(raw.getStatusCode().value()).isEqualTo(200);
		assertThat(raw.getHeaders().getContentType().toString()).contains("application/json");
		assertThat(raw.getBody()).as("原文直发（非 R 信封）").isEqualTo(MAP_JSON);

		assertThat(get("/system/track/sourcemap/raw?id=" + id, null).getStatusCode().value())
			.as("无 token raw 应 401").isEqualTo(401);
		assertThat(get("/system/track/sourcemap/raw?id=" + id, lowToken).getStatusCode().value())
			.as("低权（无 sys:track-error:list）raw 应 403").isEqualTo(403);
		JsonNode cross = readBody(get("/system/track/sourcemap/raw?id=" + id, tenantBToken));
		assertThat(cross.path("code").asInt()).as("跨租户 raw 应 400").isEqualTo(400);
	}

	// ==================== 错误告警引擎 ====================

	/** ⑤ 规则 A：新指纹首次出现触发 1 条站内信（租户管理员收件）；同指纹再来不重复（7 天 SETNX 去重） */
	@Test
	void ruleANewFingerprintAlertsOnce() throws InterruptedException {
		pushError(APP_ALERT, FP_NEW, "/g101-page");

		awaitUntil("规则 A 告警站内信落库", () -> alertMessageCount(FP_NEW) == 1L);
		// 收件人含本租户管理员（平台租户 admin）
		awaitUntil("管理员收件记录", () -> {
			Row row = Db.selectOneBySql("SELECT count(*) AS c FROM sys_message_user mu"
				+ " JOIN sys_message m ON m.id = mu.message_id"
				+ " WHERE m.title = ? AND m.content LIKE ? AND mu.user_id = ?",
				TrackConstants.ALERT_MESSAGE_TITLE, "%" + FP_NEW + "%", adminId);
			return row.getLong("c") == 1L;
		});

		// 同指纹再发：规则 A 去重不重复告（阈值 3 未达，规则 B 亦不触发）
		pushError(APP_ALERT, FP_NEW, "/g101-page");
		Thread.sleep(2500);
		assertThat(alertMessageCount(FP_NEW)).as("同指纹不重复首告").isEqualTo(1L);
	}

	/** ⑥ 规则 B：连发到阈值触发 1 条；窗口内越线后继续来不重复（窗级抑制键） */
	@Test
	void ruleBThresholdAlertsOncePerWindow() throws InterruptedException {
		// 预置「指纹已见过」（规则 A 去重键），隔离规则 A 干扰，专验规则 B
		redis.opsForValue().setIfAbsent(TrackConstants.ALERT_NEW_KEY_PREFIX + APP_ALERT + ":" + FP_TH, "1",
			java.time.Duration.ofSeconds(TrackConstants.ALERT_NEW_TTL_SECONDS));

		pushError(APP_ALERT, FP_TH, "/g101-freq");
		pushError(APP_ALERT, FP_TH, "/g101-freq");
		Thread.sleep(2500);
		assertThat(alertMessageCount(FP_TH)).as("阈值（3 次）未达不告").isEqualTo(0L);

		pushError(APP_ALERT, FP_TH, "/g101-freq");
		awaitUntil("规则 B 达阈值告警", () -> alertMessageCount(FP_TH) == 1L);

		pushError(APP_ALERT, FP_TH, "/g101-freq");
		Thread.sleep(2500);
		assertThat(alertMessageCount(FP_TH)).as("同窗内不重复告").isEqualTo(1L);
	}

	/** ⑦ alert_enabled=0 的应用：$error 正常落库但绝不触发告警 */
	@Test
	void alertDisabledNoMessage() throws InterruptedException {
		pushError(APP_OFF, FP_OFF, "/g101-off");
		awaitUntil("事件落库（消费已完成）", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE app_key = ? AND event_name = '$error'"
				+ " AND error_fingerprint = ?", APP_OFF, FP_OFF) == 1L);
		Thread.sleep(1000);
		assertThat(alertMessageCount(FP_OFF)).as("告警开关关闭不触发").isEqualTo(0L);
	}

	/** ⑧ 告警信内容：含事件名/指纹/次数/页面/时间/查看链接等关键字段 */
	@Test
	void alertContentContainsKeyFields() {
		pushError(APP_ALERT, FP_CONTENT, "/g101-content");
		awaitUntil("内容断言用告警信落库", () -> alertMessageCount(FP_CONTENT) == 1L);
		Row row = Db.selectOneBySql("SELECT content FROM sys_message WHERE title = ? AND content LIKE ?"
			+ " ORDER BY id DESC LIMIT 1", TrackConstants.ALERT_MESSAGE_TITLE, "%" + FP_CONTENT + "%");
		String content = row.getString("content");
		assertThat(content).contains(TrackConstants.EVENT_ERROR);
		assertThat(content).contains(FP_CONTENT);
		assertThat(content).contains("新错误指纹");
		assertThat(content).contains("本次计数：1");
		assertThat(content).contains("/g101-content");
		assertThat(content).contains("（UTC）");
		assertThat(content).contains(TrackConstants.ALERT_ERROR_LINK);
		assertThat(content).contains("G101告警应用");
	}

	// ---------- 测试工具 ----------

	/** 测试应用直灌（alert_* 两列一并落；ON CONFLICT 幂等保配置） */
	private void seedApp(String appKey, String tenantId, int alertEnabled, int alertThreshold) {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(
			"INSERT INTO track_app (id, app_key, app_name, platform, tenant_id, sample_rate, enabled,"
				+ " alert_enabled, alert_threshold, create_time, update_time, is_deleted)"
				+ " VALUES (?, ?, ?, 'web', ?, 100, 1, ?, ?, now(), now(), 0)"
				+ " ON CONFLICT (app_key) WHERE is_deleted = 0 DO UPDATE SET tenant_id = EXCLUDED.tenant_id,"
				+ " enabled = 1, alert_enabled = EXCLUDED.alert_enabled, alert_threshold = EXCLUDED.alert_threshold",
			IdUtil.getSnowflakeNextId(), appKey, "G101告警应用-" + appKey, tenantId, alertEnabled, alertThreshold));
	}

	/** multipart 上传 sourcemap（不断言，返回原始响应供各用例自判） */
	private ResponseEntity<String> uploadSourcemap(String token, byte[] content, String filename,
												   String appKey, String release) {
		HttpHeaders headers = authHeaders(token);
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return filename;
			}
		});
		body.add("appKey", appKey);
		body.add("release", release);
		return rest.exchange("/system/track/sourcemap/upload", HttpMethod.POST,
			new HttpEntity<>(body, headers), String.class);
	}

	/** 经 /track/collect 真实摄入一条 $error（指定指纹与页面） */
	private void pushError(String appKey, String fingerprint, String urlPath) {
		Map<String, Object> props = new HashMap<>();
		props.put("message", "g101 boom");
		props.put("error_fingerprint", fingerprint);
		props.put("url_path", urlPath);
		props.put("release", "1.0.0");
		ResponseEntity<String> resp = post("/track/collect",
			payload(appKey, List.of(event(TrackConstants.EVENT_ERROR, uuid(), uuid(), props))), null);
		assertThat(readBody(resp).path("data").path("received").asInt()).as("$error 入队").isEqualTo(1);
	}

	/** 告警站内信计数（按标题 + 指纹片段收窄） */
	private long alertMessageCount(String fingerprintFragment) {
		Row row = Db.selectOneBySql("SELECT count(*) AS c FROM sys_message WHERE title = ? AND content LIKE ?",
			TrackConstants.ALERT_MESSAGE_TITLE, "%" + fingerprintFragment + "%");
		return row.getLong("c");
	}

	private JsonNode readBodyAssertOk(ResponseEntity<String> resp) {
		JsonNode json = readBody(resp);
		assertThat(json.path("code").asInt()).as("请求应成功：" + json).isEqualTo(200);
		return json.path("data");
	}

	/** 建零权限角色 + 低权用户并授权（TrackAnalysisApiTest 同款），返回其 token */
	private String createLowPrivilegeUser() {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "G101零权限角色");
		role.put("roleCode", ROLE_CODE);
		role.put("sort", 99);
		role.put("dataScope", 1);
		JsonNode roleResp = readBody(post("/system/role/submit", role, adminToken));
		assertThat(roleResp.path("code").asInt()).as("建角色：" + roleResp.path("msg").asText()).isEqualTo(200);

		Map<String, Object> user = new HashMap<>();
		user.put("username", LOW_USERNAME);
		user.put("nickname", "G101低权用户");
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
