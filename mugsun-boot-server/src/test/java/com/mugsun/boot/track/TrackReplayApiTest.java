package com.mugsun.boot.track;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.system.service.ParamService;
import com.mugsun.boot.track.job.TrackReplayCleanJob;
import com.mybatisflex.core.datasource.DataSourceKey;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话回放集成测试（G100）：/track/replay 摄入（校验链/幂等/体积上限/封禁）→ 异步落储（对象存储真文件 +
 * track_replay 元数据 + track_session.has_replay 置位）→ 读取 API（page/detail/data + 401/403/跨租户）
 * → 保留期清理任务 → 操作日志留痕 → /track/config 回放开关下发 → 会话事件打点端点与墙钟锚点投影（G105）。
 * <p>存储落 target/it-files/（基座动态属性），断言真实文件存在与字节一致；测试数据测后清理
 * （track 域表行 + 对象文件；租户/用户脚手架同 TrackAnalysisApiTest 先例留置，容器随 JVM 销毁）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrackReplayApiTest extends AbstractTrackIntegrationTest {

	private static final String APP_R = "it-replay-a";
	private static final String APP_R_OFF = "it-replay-off";
	private static final String APP_R_B = "it-replay-b";
	private static final String LOW_USERNAME = "it-replay-lowpriv";
	private static final String LOW_PASSWORD = "123456";
	private static final String ROLE_CODE = "it-replay-noperm";
	/** 本地存储落盘根（基座动态属性 dromara.x-file-storage.local-plus[0].storage-path） */
	private static final String STORAGE_ROOT = "target/it-files/";

	@Autowired
	private ParamService paramService;
	@Autowired
	private TrackReplayCleanJob cleanJob;

	private String adminToken;
	private Long adminId;
	private String tenantBCode;
	private String tenantBToken;
	private String lowToken;

	/** API 用例主会话（A 应用，2 块）；B 租户会话（跨租户可见性正向用例） */
	private String apiSession;
	private String apiSessionB;
	private String apiDistinct;
	private Block apiBlock0;
	private Block apiBlock1;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		adminId = currentUserId(ADMIN_USERNAME);

		// 第二租户（自动初始化其 admin/123456 管理员，持租户内 * 通配）+ 低权用户（零权限角色）
		Map<String, Object> tenantBody = new HashMap<>();
		tenantBody.put("tenantName", "回放测试租户");
		tenantBody.put("contactUser", "回放测试");
		JsonNode tenantResp = readBody(post("/system/tenant/create", tenantBody, adminToken));
		assertThat(tenantResp.path("code").asInt()).as("建租户：" + tenantResp.path("msg").asText()).isEqualTo(200);
		tenantBCode = tenantResp.path("data").asText();
		tenantBToken = login(tenantBCode, ADMIN_USERNAME, ADMIN_PASSWORD);
		lowToken = createLowPrivilegeUser();

		seedApp(APP_R, PLATFORM_TENANT, 1);
		seedApp(APP_R_OFF, PLATFORM_TENANT, 0);
		seedApp(APP_R_B, tenantBCode, 1);

		// 主会话：先经 collect 建立会话（事件流裁定 distinct_id/entry_path），再传 2 个回放块
		apiSession = uuid();
		apiDistinct = uuid();
		collectPageview(APP_R, apiSession, apiDistinct, "/replay-entry");
		awaitUntil("主会话落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_session WHERE session_id = ?", apiSession) == 1L);
		apiBlock0 = block(apiSession, 0, List.of(
			rrweb(2, 1000), rrweb(3, 3000)));
		apiBlock1 = block(apiSession, 1, List.of(
			rrweb(3, 5000), rrweb(3, 9000), rrweb(2, 12000)));
		assertThat(postReplay(APP_R, apiBlock0).getStatusCode().value()).isEqualTo(200);
		assertThat(postReplay(APP_R, apiBlock1).getStatusCode().value()).isEqualTo(200);
		awaitUntil("主会话回放 2 块落储", () -> {
			Row r = trackRow("SELECT last_seq FROM track_replay WHERE session_id = ? AND is_deleted = 0", apiSession);
			return r != null && r.getInt("last_seq") == 1;
		});

		// B 租户会话（1 块）
		apiSessionB = uuid();
		collectPageview(APP_R_B, apiSessionB, uuid(), "/b-entry");
		awaitUntil("B 会话落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_session WHERE session_id = ?", apiSessionB) == 1L);
		assertThat(postReplay(APP_R_B, block(apiSessionB, 0, List.of(rrweb(2, 1000)))).getStatusCode().value())
			.isEqualTo(200);
		awaitUntil("B 会话回放落储", () -> trackLong(
			"SELECT count(*) AS c FROM track_replay WHERE session_id = ? AND is_deleted = 0", apiSessionB) == 1L);
	}

	@AfterAll
	void cleanup() throws IOException {
		// 对象文件（测后清理，防 target/it-files 跨用例膨胀）
		for (String appKey : List.of(APP_R, APP_R_B, APP_R_OFF)) {
			File dir = new File(STORAGE_ROOT + "mugsun/replay/" + appKey);
			if (dir.exists()) {
				try (var walk = Files.walk(dir.toPath())) {
					walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
				}
			}
		}
		// track 域行（事件/会话/回放/应用；Redis 键 TTL 25h 自然过期，容器随 JVM 销毁不清）
		DataSourceKey.use(TrackConstants.DS_KEY, () -> {
			Db.updateBySql("DELETE FROM track_replay WHERE app_key IN (?, ?, ?)", APP_R, APP_R_B, APP_R_OFF);
			Db.updateBySql("DELETE FROM track_session WHERE app_key IN (?, ?, ?)", APP_R, APP_R_B, APP_R_OFF);
			Db.updateBySql("DELETE FROM track_event WHERE app_key IN (?, ?, ?)", APP_R, APP_R_B, APP_R_OFF);
			Db.updateBySql("DELETE FROM track_app WHERE app_key IN (?, ?, ?)", APP_R, APP_R_B, APP_R_OFF);
		});
	}

	// ==================== 摄入与落储 ====================

	/** ① 合法块上传 → 异步落储：元数据行（session/seq/size/时长/事件数/身份回填）+ 存储真文件字节一致 + has_replay 置位 */
	@Test
	void legalBlockPersistsToStorageAndMetadata() throws IOException {
		String sessionId = uuid();
		String distinctId = uuid();
		collectPageview(APP_R, sessionId, distinctId, "/entry-one");
		awaitUntil("会话落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_session WHERE session_id = ?", sessionId) == 1L);

		Block block = block(sessionId, 0, List.of(rrweb(2, 2000), rrweb(3, 6000)));
		ResponseEntity<String> resp = postReplay(APP_R, block);
		assertThat(resp.getStatusCode().value()).isEqualTo(200);
		JsonNode data = readBody(resp).path("data");
		assertThat(data.path("accepted").asBoolean()).isTrue();
		assertThat(data.path("duplicated").asBoolean()).isFalse();

		awaitUntil("回放元数据落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_replay WHERE session_id = ? AND is_deleted = 0", sessionId) == 1L);
		Row row = trackRow("SELECT app_key, tenant_id, distinct_id, user_id, duration_ms, page_count,"
			+ " rrweb_events, size_bytes, has_error, entry_path, storage_key, last_seq, storage_platform"
			+ " FROM track_replay WHERE session_id = ?", sessionId);
		assertThat(row.getString("app_key")).isEqualTo(APP_R);
		assertThat(row.getString("tenant_id")).as("tenant_id 服务端裁定").isEqualTo(PLATFORM_TENANT);
		assertThat(row.getString("distinct_id")).as("distinct_id 取事件流会话裁定值").isEqualTo(distinctId);
		assertThat(row.get("user_id")).as("匿名会话 user_id 为空").isNull();
		assertThat(row.getInt("last_seq")).isEqualTo(0);
		assertThat(row.getInt("rrweb_events")).as("事件条数以服务端解析为准").isEqualTo(2);
		assertThat(row.getInt("duration_ms")).as("时长 = rrweb timestamp 极差").isEqualTo(4000);
		assertThat(row.getInt("page_count")).as("全量快照(type=2)计数").isEqualTo(1);
		assertThat(row.getLong("size_bytes")).as("size_bytes = gzip 字节数").isEqualTo(block.gz().length);
		assertThat(row.getInt("has_error")).isEqualTo(0);
		assertThat(row.getString("entry_path")).as("入口路径取会话裁定值").isEqualTo("/entry-one");
		String storageKey = row.getString("storage_key");
		assertThat(storageKey).as("对象键规则 replay/{app_key}/{yyyyMM}/{session_id}/{seq}.gz")
			.matches("mugsun/replay/" + APP_R + "/\\d{6}/" + sessionId + "/0\\.gz");
		assertThat(row.getString("storage_platform")).isNotBlank();

		// 存储真文件存在且字节与上传一致（原样存储，不再压缩）
		File stored = new File(STORAGE_ROOT + storageKey);
		assertThat(stored).as("存储文件应真实存在: " + stored.getAbsolutePath()).exists();
		assertThat(Files.readAllBytes(stored.toPath())).as("读写字节一致").isEqualTo(block.gz());

		// 会话回放标记置位
		awaitUntil("has_replay 置位", () -> {
			Row s = trackRow("SELECT has_replay FROM track_session WHERE session_id = ?", sessionId);
			return s != null && s.getInt("has_replay") == 1;
		});
	}

	/** ② 同 session+seq 重发：200 duplicated=true 幂等丢弃，元数据不累加 */
	@Test
	void duplicateSeqDroppedIdempotent() {
		String sessionId = uuid();
		Block block = block(sessionId, 0, List.of(rrweb(2, 1000)));
		ResponseEntity<String> first = postReplay(APP_R, block);
		assertThat(readBody(first).path("data").path("duplicated").asBoolean()).isFalse();
		awaitUntil("首发落储", () -> trackLong(
			"SELECT count(*) AS c FROM track_replay WHERE session_id = ? AND is_deleted = 0", sessionId) == 1L);

		ResponseEntity<String> second = postReplay(APP_R, block);
		assertThat(second.getStatusCode().value()).isEqualTo(200);
		assertThat(readBody(second).path("data").path("duplicated").asBoolean())
			.as("同 session+seq 重发应幂等命中").isTrue();

		Row row = trackRow("SELECT size_bytes, rrweb_events, last_seq FROM track_replay WHERE session_id = ?", sessionId);
		assertThat(row.getLong("size_bytes")).as("重复块不累加体积").isEqualTo(block.gz().length);
		assertThat(row.getInt("rrweb_events")).as("重复块不累加事件数").isEqualTo(1);
		assertThat(row.getInt("last_seq")).isEqualTo(0);
	}

	/** ③ replay_enabled=0 的应用拒收（400）；/track/config 对该应用下发 replayEnabled=false，对开启应用下发 true */
	@Test
	void replayDisabledAppRejected() {
		ResponseEntity<String> resp = postReplay(APP_R_OFF, block(uuid(), 0, List.of(rrweb(2, 1000))));
		assertThat(resp.getStatusCode().value()).as("回放关闭应用应拒收").isEqualTo(400);
		assertThat(readBody(resp).path("msg").asText()).contains("未开启会话回放");

		JsonNode off = readBody(get("/track/config?app_key=" + APP_R_OFF, null)).path("data");
		assertThat(off.path("replayEnabled").asBoolean()).as("关闭应用下发 replayEnabled=false").isFalse();
		JsonNode on = readBody(get("/track/config?app_key=" + APP_R, null)).path("data");
		assertThat(on.path("replayEnabled").asBoolean()).as("开启应用下发 replayEnabled=true").isTrue();
		assertThat(on.path("replaySampleRate").asInt()).isEqualTo(10);
	}

	/** ④ 单块解压后超 4MB → 413（gzip 炸弹防护：高压缩比内容以解压后口径判定） */
	@Test
	void oversizedBlockRejected413() {
		// 单事件 data 段 4.3MB 重复字符：gzip 后极小，解压后超 4MB
		String big = rrweb(3, 1000, "x".repeat(4310000));
		ResponseEntity<String> resp = postReplay(APP_R, block(uuid(), 0, List.of(big)));
		assertThat(resp.getStatusCode().value()).as("解压后超 4MB 应 413").isEqualTo(413);
		assertThat(readBody(resp).path("code").asInt()).isEqualTo(413);
	}

	/** ⑤ 单会话累计超限（小阈值注入）：首块放行、顶破块 413 + 会话封禁、后续块一律 413 丢弃 */
	@Test
	void sessionCumulativeOverflowBanned() {
		paramService.setValue(TrackConstants.PARAM_REPLAY_SESSION_MAX, "250000");
		String sessionId = uuid();
		try {
			// 每块解压后 ≈150KB（filler 150000 字符）
			ResponseEntity<String> first = postReplay(APP_R,
				block(sessionId, 0, List.of(rrweb(3, 1000, "y".repeat(150000)))));
			assertThat(first.getStatusCode().value()).as("首块（≈150KB ≤ 250000）放行").isEqualTo(200);

			ResponseEntity<String> second = postReplay(APP_R,
				block(sessionId, 1, List.of(rrweb(3, 2000, "z".repeat(150000)))));
			assertThat(second.getStatusCode().value()).as("累计 300000 > 250000 应 413").isEqualTo(413);
			assertThat(readBody(second).path("msg").asText()).contains("单会话回放体积超限");

			ResponseEntity<String> third = postReplay(APP_R,
				block(sessionId, 2, List.of(rrweb(2, 3000))));
			assertThat(third.getStatusCode().value()).as("封禁会话后续块一律 413").isEqualTo(413);
			assertThat(readBody(third).path("msg").asText()).contains("超限截断");

			awaitUntil("首块元数据落库", () -> trackLong(
				"SELECT count(*) AS c FROM track_replay WHERE session_id = ? AND is_deleted = 0", sessionId) == 1L);
			Row row = trackRow("SELECT last_seq FROM track_replay WHERE session_id = ?", sessionId);
			assertThat(row.getInt("last_seq")).as("仅首块落储（顶破块与封禁块均未入队）").isEqualTo(0);
		} finally {
			paramService.setValue(TrackConstants.PARAM_REPLAY_SESSION_MAX,
				String.valueOf(TrackConstants.DEFAULT_REPLAY_SESSION_MAX_BYTES));
		}
	}

	// ==================== 读取 API ====================

	/** ⑥ page/detail/data 200 + 结构断言；无 token 401；低权 403；跨租户查不到 */
	@Test
	void readApisAndAccessControl() {
		// page：主会话可见（含 entry_path/duration/has_error/size 字段）
		JsonNode page = readBody(get("/system/track/replay/page?appKey=" + APP_R + "&pageSize=50", adminToken)).path("data");
		JsonNode found = null;
		for (JsonNode row : page.path("records")) {
			if (apiSession.equals(row.path("sessionId").asText())) {
				found = row;
			}
		}
		assertThat(found).as("分页应含主会话").isNotNull();
		assertThat(found.path("entryPath").asText()).isEqualTo("/replay-entry");
		assertThat(found.path("durationMs").asLong()).as("时长为墙钟口径：末事件 12000 - 首事件 1000").isEqualTo(11000L);
		assertThat(found.path("hasError").asInt()).isEqualTo(0);
		assertThat(found.path("sizeBytes").asLong()).isEqualTo((long) apiBlock0.gz().length + apiBlock1.gz().length);
		assertThat(found.path("rrwebEvents").asInt()).isEqualTo(5);
		assertThat(found.path("lastSeq").asInt()).isEqualTo(1);
		assertThat(found.has("storageKey")).as("存储坐标不下发前端").isFalse();
		// hasError 过滤生效
		JsonNode errOnly = readBody(get("/system/track/replay/page?appKey=" + APP_R + "&hasError=1", adminToken)).path("data");
		for (JsonNode row : errOnly.path("records")) {
			assertThat(row.path("hasError").asInt()).isEqualTo(1);
		}

		// detail：元数据 + 块清单（键按 seq 推导）
		JsonNode detail = readBody(get("/system/track/replay/detail?sessionId=" + apiSession, adminToken)).path("data");
		assertThat(detail.path("replay").path("sessionId").asText()).isEqualTo(apiSession);
		JsonNode blocks = detail.path("blocks");
		assertThat(blocks.size()).isEqualTo(2);
		assertThat(blocks.get(0).path("seq").asInt()).isEqualTo(0);
		assertThat(blocks.get(0).path("key").asText()).endsWith("/" + apiSession + "/0.gz");
		assertThat(blocks.get(1).path("key").asText()).endsWith("/" + apiSession + "/1.gz");

		// data：读取块内容（服务端已解压明文）
		ResponseEntity<String> data = get("/system/track/replay/data?sessionId=" + apiSession + "&seq=1", adminToken);
		assertThat(data.getStatusCode().value()).isEqualTo(200);

		// 无 token → 401
		assertThat(get("/system/track/replay/page?appKey=" + APP_R, null).getStatusCode().value()).isEqualTo(401);
		assertThat(get("/system/track/replay/data?sessionId=" + apiSession + "&seq=0", null).getStatusCode().value())
			.isEqualTo(401);

		// 低权（零权限角色）→ 403（列表码与查看码分别拦截）
		assertThat(get("/system/track/replay/page?appKey=" + APP_R, lowToken).getStatusCode().value()).isEqualTo(403);
		assertThat(get("/system/track/replay/data?sessionId=" + apiSession + "&seq=0", lowToken).getStatusCode().value())
			.isEqualTo(403);

		// 跨租户：B 租户管理员（通配权限）查 A（平台租户）会话 → 命中「不存在」；分页不可见
		JsonNode crossData = readBody(get("/system/track/replay/data?sessionId=" + apiSession + "&seq=0", tenantBToken));
		assertThat(crossData.path("code").asInt()).as("跨租户读块应 400（回放不存在）").isEqualTo(400);
		JsonNode crossPage = readBody(get("/system/track/replay/page?appKey=" + APP_R, tenantBToken)).path("data");
		assertThat(crossPage.path("totalRow").asLong()).as("跨租户分页不可见 A 应用回放").isEqualTo(0L);

		// B 租户本租户会话：page/data 正向可读
		JsonNode ownPage = readBody(get("/system/track/replay/page?appKey=" + APP_R_B, tenantBToken)).path("data");
		assertThat(ownPage.path("totalRow").asLong()).as("本租户回放可见").isGreaterThanOrEqualTo(1L);
		assertThat(get("/system/track/replay/data?sessionId=" + apiSessionB + "&seq=0", tenantBToken)
			.getStatusCode().value()).isEqualTo(200);
	}

	/** ⑦ data 读取内容与写入一致（round-trip：响应即解压后 rrweb 事件数组 JSON） */
	@Test
	void dataRoundTripContentIdentical() throws IOException {
		ResponseEntity<String> resp = get("/system/track/replay/data?sessionId=" + apiSession + "&seq=0", adminToken);
		assertThat(resp.getStatusCode().value()).isEqualTo(200);
		JsonNode actual = om.readTree(resp.getBody());
		JsonNode expected = om.readTree(apiBlock0.json());
		assertThat(actual).as("读取内容与写入一致（服务端解压后明文）").isEqualTo(expected);
		assertThat(actual.isArray()).isTrue();
		assertThat(actual.size()).isEqualTo(2);
	}

	/** ⑧ 保留期清理：过期会话删对象 + 逻辑删元数据 + 复位 has_replay；未过期会话不受影响 */
	@Test
	void cleanJobPurgesExpired() {
		String sessionId = uuid();
		Block block = block(sessionId, 0, List.of(rrweb(2, 1000)));
		assertThat(postReplay(APP_R, block).getStatusCode().value()).isEqualTo(200);
		awaitUntil("待清理会话落储", () -> trackLong(
			"SELECT count(*) AS c FROM track_replay WHERE session_id = ? AND is_deleted = 0", sessionId) == 1L);
		Row row = trackRow("SELECT storage_key FROM track_replay WHERE session_id = ?", sessionId);
		File stored = new File(STORAGE_ROOT + row.getString("storage_key"));
		assertThat(stored).exists();

		// 构造过期（start_time 回拨 30 天，应用保留期 14 天）
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(
			"UPDATE track_replay SET start_time = now() - interval '30 days' WHERE session_id = ?", sessionId));

		String summary = cleanJob.cleanNow();
		assertThat(summary).contains("回放保留期清理");

		Row after = trackRow("SELECT is_deleted FROM track_replay WHERE session_id = ?", sessionId);
		assertThat(after.getInt("is_deleted")).as("过期元数据应逻辑删").isEqualTo(1);
		assertThat(stored).as("过期对象应物理删除").doesNotExist();
		Row session = trackRow("SELECT has_replay FROM track_session WHERE session_id = ?", sessionId);
		assertThat(session.getInt("has_replay")).as("会话回放标记应复位").isEqualTo(0);

		// 未过期主会话不受影响
		Row alive = trackRow("SELECT is_deleted FROM track_replay WHERE session_id = ?", apiSession);
		assertThat(alive.getInt("is_deleted")).as("未过期回放生还").isEqualTo(0);
	}

	/** ⑨ 操作日志留痕：回放查看写 sys_oper_log（标题/URI/操作人/参数含会话） */
	@Test
	void replayViewAuditLogged() {
		String sessionId = uuid();
		assertThat(postReplay(APP_R, block(sessionId, 0, List.of(rrweb(2, 1000)))).getStatusCode().value()).isEqualTo(200);
		awaitUntil("留痕用例回放落储", () -> trackLong(
			"SELECT count(*) AS c FROM track_replay WHERE session_id = ? AND is_deleted = 0", sessionId) == 1L);
		ResponseEntity<String> resp = get("/system/track/replay/data?sessionId=" + sessionId + "&seq=0", adminToken);
		assertThat(resp.getStatusCode().value()).isEqualTo(200);

		// 操作日志异步落库，await 轮询（业务库）
		awaitUntil("回放查看操作日志落库", () -> {
			Row log = Db.selectOneBySql(
				"SELECT count(*) AS c FROM sys_oper_log WHERE title = '查看会话回放'"
					+ " AND request_uri = '/system/track/replay/data' AND operator = ? AND params LIKE ?",
				String.valueOf(adminId), "%" + sessionId + "%");
			return log.getLong("c") >= 1L;
		});
	}

	/** ⑩ gzip:false 明文块（pagehide 收尾块协议）：base64(明文 JSON) 摄入 → 服务端补压落储 → data 读回一致 */
	@Test
	void plainBlockAcceptedAndRecompressed() throws IOException {
		String sessionId = uuid();
		String json = "[" + String.join(",", List.of(rrweb(2, 1000), rrweb(3, 4000))) + "]";
		ResponseEntity<String> resp = postReplayPlain(APP_R, sessionId, 0, json);
		assertThat(resp.getStatusCode().value()).as("gzip:false 明文块应收：" + resp.getBody()).isEqualTo(200);
		assertThat(readBody(resp).path("data").path("accepted").asBoolean()).isTrue();

		awaitUntil("明文块元数据落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_replay WHERE session_id = ? AND is_deleted = 0", sessionId) == 1L);
		Row row = trackRow("SELECT rrweb_events, duration_ms, storage_key FROM track_replay WHERE session_id = ?",
			sessionId);
		assertThat(row.getInt("rrweb_events")).as("事件条数以服务端解析为准").isEqualTo(2);
		assertThat(row.getInt("duration_ms")).isEqualTo(3000);

		// 落储恒 gzip：文件魔数 1f8b（明文块服务端补压，读侧单一解压路径）
		File stored = new File(STORAGE_ROOT + row.getString("storage_key"));
		byte[] bytes = Files.readAllBytes(stored.toPath());
		assertThat(bytes[0] & 0xff).as("落储字节应为 gzip（魔数 1f）").isEqualTo(0x1f);
		assertThat(bytes[1] & 0xff).as("落储字节应为 gzip（魔数 8b）").isEqualTo(0x8b);

		// data 端点读回明文数组与上传内容一致
		ResponseEntity<String> data = get("/system/track/replay/data?sessionId=" + sessionId + "&seq=0", adminToken);
		assertThat(data.getStatusCode().value()).isEqualTo(200);
		assertThat(om.readTree(data.getBody())).as("明文块读取还原一致").isEqualTo(om.readTree(json));

		// 明文界兜底：base64 文本超上限（≈1.41MB）→ 413（先命中 payload 长度界；pagehide 收尾块为增量小量，此界足够）
		String big = "[" + rrweb(3, 1000, "x".repeat(1150000)) + "]";
		assertThat(postReplayPlain(APP_R, uuid(), 0, big).getStatusCode().value())
			.as("明文 base64 超上限应 413").isEqualTo(413);
		// 明文非 JSON → 400
		assertThat(postReplayPlain(APP_R, uuid(), 0, "not-json").getStatusCode().value())
			.as("明文非合法 JSON 应 400").isEqualTo(400);
	}

	// ---------- 测试工具 ----------

	/** ⑪ 回放会话事件打点（G105）：按 ts 升序返回精确三字段；空会话/不存在会话空数组；跨租户空数组；
	 *  401/403；page/detail 投影含 firstEventTs/lastEventTs（T5 墙钟锚点） */
	@Test
	void replaySessionEventsEndpoint() {
		String sessionId = uuid();
		String distinctId = uuid();
		long t0 = System.currentTimeMillis();
		// 两事件（$pageview → $click，ts 递增）：打点按 received_at 升序下发
		collectEvent(APP_R, sessionId, distinctId, "$pageview", t0, "/evt-a");
		awaitUntil("打点钱会话落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_session WHERE session_id = ?", sessionId) == 1L);
		collectEvent(APP_R, sessionId, distinctId, "$click", t0 + 1000, "/evt-b");
		awaitUntil("打点两事件落库", () -> trackLong(
			"SELECT count(*) AS c FROM track_event WHERE session_id = ?", sessionId) == 2L);

		// 契约：精确字段 {eventName, ts(epoch ms), urlPath} 按 ts 升序
		ResponseEntity<String> resp = get(
			"/system/track/replay/events?appKey=" + APP_R + "&sessionId=" + sessionId, adminToken);
		assertThat(resp.getStatusCode().value()).isEqualTo(200);
		JsonNode events = readBody(resp).path("data");
		assertThat(events.size()).as("会话两事件全部下发").isEqualTo(2);
		JsonNode first = events.get(0);
		assertThat(first.size()).as("打点行精确三字段").isEqualTo(3);
		assertThat(first.path("eventName").asText()).isEqualTo("$pageview");
		assertThat(first.path("urlPath").asText()).isEqualTo("/evt-a");
		assertThat(first.path("ts").asLong()).isEqualTo(t0);
		JsonNode second = events.get(1);
		assertThat(second.path("eventName").asText()).isEqualTo("$click");
		assertThat(second.path("urlPath").asText()).isEqualTo("/evt-b");
		assertThat(second.path("ts").asLong()).as("打点按 ts 升序").isGreaterThan(first.path("ts").asLong());

		// 会话存在但无事件（直灌会话行）→ 空数组；会话不存在 → 空数组（均不报错）
		String silentSession = uuid();
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(
			"INSERT INTO track_session (id, session_id, app_key, tenant_id, distinct_id, start_time, end_time,"
				+ " create_time, update_time, is_deleted) VALUES (?, ?, ?, ?, ?, now(), now(), now(), now(), 0)",
			IdUtil.getSnowflakeNextId(), silentSession, APP_R, PLATFORM_TENANT, uuid()));
		JsonNode silent = readBody(get(
			"/system/track/replay/events?appKey=" + APP_R + "&sessionId=" + silentSession, adminToken)).path("data");
		assertThat(silent.isArray()).isTrue();
		assertThat(silent.size()).as("无事件会话应空数组").isEqualTo(0);
		JsonNode missing = readBody(get(
			"/system/track/replay/events?appKey=" + APP_R + "&sessionId=" + uuid(), adminToken)).path("data");
		assertThat(missing.size()).as("不存在会话应空数组（不报错）").isEqualTo(0);

		// 跨租户：B 租户管理员查 A 会话 → 空数组（与「不存在」同口径，不暴露存在性）
		JsonNode cross = readBody(get(
			"/system/track/replay/events?appKey=" + APP_R + "&sessionId=" + sessionId, tenantBToken)).path("data");
		assertThat(cross.size()).as("跨租户打点应空数组").isEqualTo(0);

		// 无 token → 401；低权（无回放列表码）→ 403
		assertThat(get("/system/track/replay/events?appKey=" + APP_R + "&sessionId=" + sessionId, null)
			.getStatusCode().value()).isEqualTo(401);
		assertThat(get("/system/track/replay/events?appKey=" + APP_R + "&sessionId=" + sessionId, lowToken)
			.getStatusCode().value()).isEqualTo(403);

		// 投影：回放行下发 firstEventTs/lastEventTs（主会话 rrweb 时间戳 1000..12000，LEAST/GREATEST 归并）
		JsonNode detail = readBody(get("/system/track/replay/detail?sessionId=" + apiSession, adminToken)).path("data");
		assertThat(detail.path("replay").path("firstEventTs").asLong()).isEqualTo(1000L);
		assertThat(detail.path("replay").path("lastEventTs").asLong()).isEqualTo(12000L);
	}

	// ---------- 测试工具 ----------

	/** 播种测试应用（幂等；replay_enabled 逐应用指定，采样 10% 保留 14 天） */
	private void seedApp(String appKey, String tenantId, int replayEnabled) {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(
			"INSERT INTO track_app (id, app_key, app_name, platform, tenant_id, sample_rate, enabled,"
				+ " replay_enabled, replay_sample_rate, replay_retention_days, create_time, update_time, is_deleted)"
				+ " VALUES (?, ?, ?, 'web', ?, 100, 1, ?, 10, 14, now(), now(), 0)"
				+ " ON CONFLICT (app_key) WHERE is_deleted = 0 DO UPDATE SET"
				+ " tenant_id = EXCLUDED.tenant_id, enabled = 1, replay_enabled = EXCLUDED.replay_enabled",
			IdUtil.getSnowflakeNextId(), appKey, "回放测试-" + appKey, tenantId, replayEnabled));
	}

	/** 经真实摄入链路建立一个会话（$pageview 带入口路径） */
	private void collectPageview(String appKey, String sessionId, String distinctId, String urlPath) {
		collectEvent(appKey, sessionId, distinctId, "$pageview", System.currentTimeMillis(), urlPath);
	}

	/** 经真实摄入链路打一条事件（显式 ts 与 url_path；打点升序/精确 ts 断言用） */
	private void collectEvent(String appKey, String sessionId, String distinctId, String eventName, long ts, String urlPath) {
		Map<String, Object> event = new HashMap<>();
		event.put("event_id", uuid());
		event.put("event", eventName);
		event.put("ts", ts);
		event.put("distinct_id", distinctId);
		event.put("session_id", sessionId);
		event.put("props", Map.of("url_path", urlPath));
		Map<String, Object> payload = new HashMap<>();
		payload.put("app_key", appKey);
		payload.put("schema_version", 1);
		payload.put("sdk", Map.of("platform", "web", "version", "0.0.1-it"));
		payload.put("sent_at", System.currentTimeMillis());
		payload.put("events", List.of(event));
		ResponseEntity<String> resp = post("/track/collect", payload, null);
		assertThat(resp.getStatusCode().value()).as("会话建立事件摄入").isEqualTo(200);
	}

	/** 回放块载体：rrweb 事件数组 JSON 原文 + gzip 字节 + base64 文本 */
	private record Block(String sessionId, int seq, String json, byte[] gz, String payloadB64) {
	}

	/** 组装回放块（gzip + base64，与 SDK 协议同构） */
	private Block block(String sessionId, int seq, List<String> rrwebEvents) {
		String json = "[" + String.join(",", rrwebEvents) + "]";
		byte[] gz = gzip(json.getBytes(StandardCharsets.UTF_8));
		return new Block(sessionId, seq, json, gz, Base64.getEncoder().encodeToString(gz));
	}

	/** rrweb 事件（type/timestamp 最小结构） */
	private String rrweb(int type, long timestamp) {
		return "{\"type\":" + type + ",\"timestamp\":" + timestamp + ",\"data\":{}}";
	}

	/** rrweb 事件（带填充文本，造体积用例用） */
	private String rrweb(int type, long timestamp, String filler) {
		return "{\"type\":" + type + ",\"timestamp\":" + timestamp + ",\"data\":\"" + filler + "\"}";
	}

	/** 上传回放块（协议：{app_key, session_id, seq, event_count, gzip:true, payload:<base64>}） */
	private ResponseEntity<String> postReplay(String appKey, Block block) {
		Map<String, Object> body = new HashMap<>();
		body.put("app_key", appKey);
		body.put("session_id", block.sessionId());
		body.put("seq", block.seq());
		body.put("event_count", 1);
		body.put("gzip", true);
		body.put("payload", block.payloadB64());
		return post("/track/replay", body, null);
	}

	/** 上传明文回放块（gzip:false = base64(明文 JSON)，SDK pagehide 收尾块协议） */
	private ResponseEntity<String> postReplayPlain(String appKey, String sessionId, int seq, String json) {
		Map<String, Object> body = new HashMap<>();
		body.put("app_key", appKey);
		body.put("session_id", sessionId);
		body.put("seq", seq);
		body.put("event_count", 1);
		body.put("gzip", false);
		body.put("payload", Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));
		return post("/track/replay", body, null);
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

	/** 建零权限角色 + 低权用户并授权（TrackAnalysisApiTest 同款），返回其 token */
	private String createLowPrivilegeUser() {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "回放测试零权限角色");
		role.put("roleCode", ROLE_CODE);
		role.put("sort", 99);
		role.put("dataScope", 1);
		JsonNode roleResp = readBody(post("/system/role/submit", role, adminToken));
		assertThat(roleResp.path("code").asInt()).as("建角色：" + roleResp.path("msg").asText()).isEqualTo(200);

		Map<String, Object> user = new HashMap<>();
		user.put("username", LOW_USERNAME);
		user.put("nickname", "回放测试低权用户");
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

	private String uuid() {
		return UUID.randomUUID().toString();
	}
}
