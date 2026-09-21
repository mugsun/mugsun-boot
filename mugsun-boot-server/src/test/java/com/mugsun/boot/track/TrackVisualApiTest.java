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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 圈选式可视化埋点集成测试（G104）：令牌签发（Redis HASH 结构/归属）→ 匿名草稿上报（校验链/上限/限流）
 * → 管理端 drafts/confirm/discard（自然键「查-改或插」+ track_event_def 同步）→ 规则 CRUD
 * → /track/config visualRules 下发与移除（缓存 evict 即时生效）→ 401/403/跨租户隔离。
 * <p>令牌/草稿 Redis 键测后清理（限流键 70s TTL 自然过期，同 TrackReplayApiTest 先例）；
 * track 域行物理清理（租户/用户脚手架留置，容器随 JVM 销毁）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TrackVisualApiTest extends AbstractTrackIntegrationTest {

	private static final String APP_V = "it-visual-a";
	private static final String APP_V_B = "it-visual-b";
	private static final String LOW_USERNAME = "it-visual-lowpriv";
	private static final String LOW_PASSWORD = "123456";
	private static final String ROLE_CODE = "it-visual-noperm";

	@Autowired
	private StringRedisTemplate redis;

	private String adminToken;
	private Long adminId;
	private String tenantBCode;
	private String tenantBToken;
	private String lowToken;

	/** 本类签发的全部令牌（@AfterAll 清理对应 Redis 键） */
	private final List<String> issuedTokens = new ArrayList<>();

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		adminId = currentUserId(ADMIN_USERNAME);

		// 第二租户（自动初始化其 admin/123456 管理员，持租户内 * 通配）+ 低权用户（零权限角色）
		Map<String, Object> tenantBody = new HashMap<>();
		tenantBody.put("tenantName", "圈选测试租户");
		tenantBody.put("contactUser", "圈选测试");
		JsonNode tenantResp = readBody(post("/system/tenant/create", tenantBody, adminToken));
		assertThat(tenantResp.path("code").asInt()).as("建租户：" + tenantResp.path("msg").asText()).isEqualTo(200);
		tenantBCode = tenantResp.path("data").asText();
		tenantBToken = login(tenantBCode, ADMIN_USERNAME, ADMIN_PASSWORD);
		lowToken = createLowPrivilegeUser();

		seedApp(APP_V, PLATFORM_TENANT);
		seedApp(APP_V_B, tenantBCode);
	}

	@AfterAll
	void cleanup() {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> {
			Db.updateBySql("DELETE FROM track_visual_rule WHERE app_key IN (?, ?)", APP_V, APP_V_B);
			Db.updateBySql("DELETE FROM track_event_def WHERE app_key IN (?, ?)", APP_V, APP_V_B);
			Db.updateBySql("DELETE FROM track_app WHERE app_key IN (?, ?)", APP_V, APP_V_B);
		});
		for (String token : issuedTokens) {
			redis.delete(TrackConstants.VISUAL_TOKEN_KEY_PREFIX + token);
			redis.delete(TrackConstants.VISUAL_DRAFT_KEY_PREFIX + token);
		}
	}

	// ==================== 主链路 ====================

	/** ① 令牌签发（HASH 结构/url 拼接）→ 草稿上报 → drafts 字段精确断言 → confirm 成规则
	 *  （track_visual_rule + track_event_def 行断言）→ config 下发 visualRules → 重复 confirm 走更新不增行 → discard */
	@Test
	void tokenDraftConfirmConfigFlow() {
		// 令牌签发：targetUrl 已带 ? → & 追加激活参数
		JsonNode data = readBody(post("/system/track/visual/token",
			Map.of("appKey", APP_V, "targetUrl", "https://example.com/mall?from=it"), adminToken)).path("data");
		String token = data.path("token").asText();
		assertThat(token).as("令牌为 48 位随机 hex").matches("[0-9a-f]{48}");
		assertThat(data.path("url").asText())
			.isEqualTo("https://example.com/mall?from=it&__mst_inspect=" + token);
		assertThat(data.path("expireSeconds").asLong()).isEqualTo(TrackConstants.VISUAL_TOKEN_TTL_SECONDS);
		issuedTokens.add(token);

		// HASH 结构断言（appKey/tenantId/userId + TTL）
		Map<Object, Object> hash = redis.opsForHash().entries(TrackConstants.VISUAL_TOKEN_KEY_PREFIX + token);
		assertThat(hash.get("appKey")).isEqualTo(APP_V);
		assertThat(hash.get("tenantId")).as("令牌归属 = 应用归属租户").isEqualTo(PLATFORM_TENANT);
		assertThat(hash.get("userId")).isEqualTo(String.valueOf(adminId));
		long ttl = redis.getExpire(TrackConstants.VISUAL_TOKEN_KEY_PREFIX + token);
		assertThat(ttl).as("令牌 TTL").isGreaterThan(0).isLessThanOrEqualTo(TrackConstants.VISUAL_TOKEN_TTL_SECONDS);

		// targetUrl 无 ? → ? 追加；targetUrl 缺省 → url=null（前端自拼 origin）
		JsonNode bare = readBody(post("/system/track/visual/token",
			Map.of("appKey", APP_V, "targetUrl", "https://example.com"), adminToken)).path("data");
		assertThat(bare.path("url").asText()).startsWith("https://example.com?__mst_inspect=");
		issuedTokens.add(bare.path("token").asText());
		JsonNode noUrl = readBody(post("/system/track/visual/token", Map.of("appKey", APP_V), adminToken)).path("data");
		assertThat(noUrl.has("url") && noUrl.path("url").isNull()).as("targetUrl 空时 url=null").isTrue();
		issuedTokens.add(noUrl.path("token").asText());

		// 草稿上报（匿名）：一全量字段、一仅必填
		Map<String, Object> draft1 = new HashMap<>();
		draft1.put("token", token);
		draft1.put("event_name", "visual_click");
		draft1.put("selector", "div#buy > span.btn");
		draft1.put("route_path", "/mall");
		draft1.put("match_text", "立即购买");
		draft1.put("page_url", "/mall");
		draft1.put("element_text", "立即购买");
		ResponseEntity<String> resp1 = post("/track/visual/draft", draft1, null);
		assertThat(resp1.getStatusCode().value()).as("草稿1上报：" + resp1.getBody()).isEqualTo(200);
		assertThat(readBody(resp1).path("data").path("received").asBoolean()).isTrue();
		Map<String, Object> draft2 = new HashMap<>();
		draft2.put("token", token);
		draft2.put("event_name", "visual_plain");
		draft2.put("selector", "ul > li:nth-of-type(2)");
		assertThat(post("/track/visual/draft", draft2, null).getStatusCode().value()).isEqualTo(200);

		// drafts 列表字段精确断言（RPUSH 顺序）
		JsonNode drafts = readBody(get("/system/track/visual/drafts?token=" + token, adminToken)).path("data");
		assertThat(drafts.size()).isEqualTo(2);
		JsonNode d1 = drafts.get(0);
		String draftId1 = d1.path("draftId").asText();
		assertThat(draftId1).isNotBlank();
		assertThat(d1.path("eventName").asText()).isEqualTo("visual_click");
		assertThat(d1.path("selector").asText()).isEqualTo("div#buy > span.btn");
		assertThat(d1.path("routePath").asText()).isEqualTo("/mall");
		assertThat(d1.path("matchText").asText()).isEqualTo("立即购买");
		assertThat(d1.path("pageUrl").asText()).isEqualTo("/mall");
		assertThat(d1.path("elementText").asText()).isEqualTo("立即购买");
		assertThat(d1.path("ts").asLong()).isGreaterThan(0);
		JsonNode d2 = drafts.get(1);
		String draftId2 = d2.path("draftId").asText();
		assertThat(d2.path("routePath").isNull()).as("可空字段 null 直出").isTrue();
		assertThat(d2.path("matchText").isNull()).isTrue();

		// confirm 草稿1（eventName 缺省取草稿值）
		JsonNode rule = readBody(post("/system/track/visual/drafts/confirm",
			Map.of("token", token, "draftId", draftId1), adminToken)).path("data");
		long ruleId = rule.path("id").asLong();
		assertThat(ruleId).isGreaterThan(0);
		assertThat(rule.path("eventName").asText()).isEqualTo("visual_click");
		assertThat(rule.path("selector").asText()).isEqualTo("div#buy > span.btn");
		assertThat(rule.path("routePath").asText()).isEqualTo("/mall");
		assertThat(rule.path("matchText").asText()).isEqualTo("立即购买");
		assertThat(rule.path("status").asInt()).isEqualTo(1);
		assertThat(rule.path("source").asText()).isEqualTo(TrackConstants.VISUAL_RULE_SOURCE);
		assertThat(rule.path("tenantId").asText()).isEqualTo(PLATFORM_TENANT);
		assertThat(rule.path("createBy").asLong()).isEqualTo(adminId);

		// track_visual_rule / track_event_def 行断言
		Row dbRule = trackRow("SELECT tenant_id, source, status FROM track_visual_rule WHERE id = ?", ruleId);
		assertThat(dbRule.getString("tenant_id")).as("规则归属 = 应用归属租户").isEqualTo(PLATFORM_TENANT);
		assertThat(dbRule.getString("source")).isEqualTo(TrackConstants.VISUAL_RULE_SOURCE);
		assertThat(dbRule.getInt("status")).isEqualTo(1);
		Row def = trackRow("SELECT display_name, description, status, tenant_id FROM track_event_def"
			+ " WHERE app_key = ? AND event_name = ?", APP_V, "visual_click");
		assertThat(def).as("confirm 同步事件定义").isNotNull();
		assertThat(def.getString("display_name")).isEqualTo("visual_click");
		assertThat(def.getString("description")).isEqualTo("圈选创建");
		assertThat(def.getInt("status")).isEqualTo(1);
		assertThat(def.getString("tenant_id")).isEqualTo(PLATFORM_TENANT);

		// drafts 剩 1
		assertThat(readBody(get("/system/track/visual/drafts?token=" + token, adminToken)).path("data").size())
			.isEqualTo(1);

		// /track/config 匿名下发 visualRules（confirm 已 evict 缓存，首轮拉取即含）
		JsonNode cfgRules = readBody(get("/track/config?app_key=" + APP_V, null)).path("data").path("visualRules");
		assertThat(cfgRules.isArray()).isTrue();
		JsonNode hit = null;
		for (JsonNode r : cfgRules) {
			if ("visual_click".equals(r.path("event").asText())) {
				hit = r;
			}
		}
		assertThat(hit).as("config 下发含已确认规则").isNotNull();
		assertThat(hit.path("selector").asText()).isEqualTo("div#buy > span.btn");
		assertThat(hit.path("routePath").asText()).isEqualTo("/mall");
		assertThat(hit.path("matchText").asText()).isEqualTo("立即购买");

		// confirm 草稿2（改名 visual_renamed）→ 新规则 + 事件定义同步
		JsonNode rule2 = readBody(post("/system/track/visual/drafts/confirm",
			Map.of("token", token, "draftId", draftId2, "eventName", "visual_renamed"), adminToken)).path("data");
		assertThat(rule2.path("eventName").asText()).isEqualTo("visual_renamed");
		assertThat(rule2.path("routePath").isNull()).as("可空自然键维 NULL 入库").isTrue();
		Row def2 = trackRow("SELECT count(*) AS c FROM track_event_def WHERE app_key = ? AND event_name = ?",
			APP_V, "visual_renamed");
		assertThat(def2.getLong("c")).isEqualTo(1L);

		// 再次 confirm 同 draftId → 400（草稿已删）
		JsonNode again = readBody(post("/system/track/visual/drafts/confirm",
			Map.of("token", token, "draftId", draftId2), adminToken));
		assertThat(again.path("code").asInt()).as("草稿确认后应已删").isEqualTo(400);
		assertThat(again.path("msg").asText()).contains("草稿不存在");

		// 重复圈选同一元素（重灌同自然键草稿）→ 走更新不增行
		draft1.put("match_text", "立即购买");
		assertThat(post("/track/visual/draft", draft1, null).getStatusCode().value()).isEqualTo(200);
		long before = trackLong("SELECT count(*) AS c FROM track_visual_rule WHERE app_key = ? AND is_deleted = 0", APP_V);
		JsonNode draftsAgain = readBody(get("/system/track/visual/drafts?token=" + token, adminToken)).path("data");
		String reDraftId = draftsAgain.get(0).path("draftId").asText();
		JsonNode reRule = readBody(post("/system/track/visual/drafts/confirm",
			Map.of("token", token, "draftId", reDraftId), adminToken)).path("data");
		assertThat(reRule.path("id").asLong()).as("自然键命中走更新").isEqualTo(ruleId);
		long after = trackLong("SELECT count(*) AS c FROM track_visual_rule WHERE app_key = ? AND is_deleted = 0", APP_V);
		assertThat(after).as("重复 confirm 不增行").isEqualTo(before);

		// discard：重灌一条后丢弃 → 列表空；再丢同 id → 400
		draft2.put("selector", "ul > li:nth-of-type(3)");
		assertThat(post("/track/visual/draft", draft2, null).getStatusCode().value()).isEqualTo(200);
		JsonNode drafts3 = readBody(get("/system/track/visual/drafts?token=" + token, adminToken)).path("data");
		String discardId = drafts3.get(0).path("draftId").asText();
		assertThat(readBody(post("/system/track/visual/drafts/discard",
			Map.of("token", token, "draftId", discardId), adminToken)).path("code").asInt()).isEqualTo(200);
		assertThat(readBody(get("/system/track/visual/drafts?token=" + token, adminToken)).path("data").size())
			.as("discard 后列表空").isEqualTo(0);
		assertThat(readBody(post("/system/track/visual/drafts/discard",
			Map.of("token", token, "draftId", discardId), adminToken)).path("code").asInt())
			.as("重复丢弃 400").isEqualTo(400);
	}

	// ==================== 草稿校验链 ====================

	/** ② 匿名草稿否定链：伪令牌 401 / 缺 token 400 / 非法事件名 400 / selector 缺失·超长 400 / 可空字段超长截断 */
	@Test
	void draftValidationNegatives() {
		String token = issueToken(adminToken, APP_V);
		Map<String, Object> valid = new HashMap<>();
		valid.put("token", token);
		valid.put("event_name", "neg_evt");
		valid.put("selector", "div.neg");

		// 伪令牌 → 401（HTTP 与信封同码）
		Map<String, Object> fake = new HashMap<>(valid);
		fake.put("token", "0".repeat(48));
		ResponseEntity<String> fakeResp = post("/track/visual/draft", fake, null);
		assertThat(fakeResp.getStatusCode().value()).isEqualTo(401);
		assertThat(readBody(fakeResp).path("code").asInt()).isEqualTo(401);

		// 缺 token → 400
		Map<String, Object> noToken = new HashMap<>(valid);
		noToken.remove("token");
		assertThat(post("/track/visual/draft", noToken, null).getStatusCode().value()).isEqualTo(400);

		// 非法事件名 → 400（$ 前缀必拒；数字开头必拒）
		for (String bad : List.of("$click", "1abc")) {
			Map<String, Object> body = new HashMap<>(valid);
			body.put("event_name", bad);
			ResponseEntity<String> resp = post("/track/visual/draft", body, null);
			assertThat(resp.getStatusCode().value()).as("非法事件名应 400: " + bad).isEqualTo(400);
		}

		// selector 缺失 → 400；超长 → 400
		Map<String, Object> noSelector = new HashMap<>(valid);
		noSelector.remove("selector");
		assertThat(post("/track/visual/draft", noSelector, null).getStatusCode().value()).isEqualTo(400);
		Map<String, Object> longSelector = new HashMap<>(valid);
		longSelector.put("selector", "a".repeat(TrackConstants.VISUAL_SELECTOR_MAX_LEN + 1));
		assertThat(post("/track/visual/draft", longSelector, null).getStatusCode().value()).isEqualTo(400);

		// 可空字段超长截断而非报错：match_text/element_text → 128，route_path → 255，page_url → 512
		Map<String, Object> trunc = new HashMap<>(valid);
		trunc.put("match_text", "文".repeat(200));
		trunc.put("element_text", "e".repeat(200));
		trunc.put("route_path", "/".repeat(300));
		trunc.put("page_url", "p".repeat(600));
		ResponseEntity<String> truncResp = post("/track/visual/draft", trunc, null);
		assertThat(truncResp.getStatusCode().value()).as("超长可空字段应截断放行").isEqualTo(200);
		JsonNode drafts = readBody(get("/system/track/visual/drafts?token=" + token, adminToken)).path("data");
		assertThat(drafts.size()).isEqualTo(1);
		JsonNode d = drafts.get(0);
		assertThat(d.path("matchText").asText().length()).isEqualTo(TrackConstants.VISUAL_MATCH_TEXT_MAX_LEN);
		assertThat(d.path("elementText").asText().length()).isEqualTo(TrackConstants.VISUAL_MATCH_TEXT_MAX_LEN);
		assertThat(d.path("routePath").asText().length()).isEqualTo(TrackConstants.DIM_MAX_LEN);
		assertThat(d.path("pageUrl").asText().length()).isEqualTo(TrackConstants.URL_MAX_LEN);

		// 管理端 drafts 伪令牌 → 400「令牌已过期」
		JsonNode expired = readBody(get("/system/track/visual/drafts?token=" + "f".repeat(48), adminToken));
		assertThat(expired.path("code").asInt()).isEqualTo(400);
		assertThat(expired.path("msg").asText()).contains("令牌已过期");
	}

	/** ③ 单令牌草稿上限 50（满 400「草稿已满请先确认」）+ 分钟窗限流（60 连发后第 61 次 429，独立令牌防干扰） */
	@Test
	void draftCapAndRateLimit() {
		String token = issueToken(adminToken, APP_V);
		Map<String, Object> draft = new HashMap<>();
		draft.put("token", token);
		draft.put("event_name", "cap_evt");
		draft.put("selector", "div.cap");

		for (int i = 1; i <= TrackConstants.VISUAL_DRAFT_MAX_PER_TOKEN; i++) {
			ResponseEntity<String> resp = post("/track/visual/draft", draft, null);
			assertThat(resp.getStatusCode().value()).as("第 " + i + " 条草稿应放行").isEqualTo(200);
		}
		// 第 51 条：草稿已满 400（限流计数继续累计）
		ResponseEntity<String> full = post("/track/visual/draft", draft, null);
		assertThat(full.getStatusCode().value()).isEqualTo(400);
		assertThat(readBody(full).path("msg").asText()).contains("草稿已满请先确认");
		// 52..60 条持续 400（满），把限流计数顶到 60
		for (int i = TrackConstants.VISUAL_DRAFT_MAX_PER_TOKEN + 2; i <= TrackConstants.VISUAL_DRAFT_RATE_LIMIT; i++) {
			post("/track/visual/draft", draft, null);
		}
		// 第 61 次：限流 429
		ResponseEntity<String> limited = post("/track/visual/draft", draft, null);
		assertThat(limited.getStatusCode().value()).as("60 连发后第 61 次应限流").isEqualTo(429);
		assertThat(readBody(limited).path("code").asInt()).isEqualTo(429);
	}

	// ==================== 权限与租户隔离 ====================

	/** ④ 未登录 401 / 低权 403 / 他租户签发 400 / 他租户操作令牌草稿 400 / 跨租户按 id 攻击 400 / 分页隔离 */
	@Test
	void managementAccessControl() {
		String token = issueToken(adminToken, APP_V);
		Map<String, Object> draft = new HashMap<>();
		draft.put("token", token);
		draft.put("event_name", "acl_evt");
		draft.put("selector", "div.acl");
		assertThat(post("/track/visual/draft", draft, null).getStatusCode().value()).isEqualTo(200);
		String draftId = readBody(get("/system/track/visual/drafts?token=" + token, adminToken))
			.path("data").get(0).path("draftId").asText();

		// 未登录 → 401（读/写端点抽查）
		assertThat(get("/system/track/visual/drafts?token=" + token, null).getStatusCode().value()).isEqualTo(401);
		assertThat(post("/system/track/visual/token", Map.of("appKey", APP_V), null).getStatusCode().value())
			.isEqualTo(401);
		assertThat(post("/system/track/visual/drafts/confirm",
			Map.of("token", token, "draftId", draftId), null).getStatusCode().value()).isEqualTo(401);
		assertThat(post("/system/track/visual/drafts/discard",
			Map.of("token", token, "draftId", draftId), null).getStatusCode().value()).isEqualTo(401);
		assertThat(get("/system/track/visual/rule/page?appKey=" + APP_V, null).getStatusCode().value()).isEqualTo(401);
		assertThat(post("/system/track/visual/rule/submit", Map.of("id", 1), null).getStatusCode().value())
			.isEqualTo(401);
		assertThat(post("/system/track/visual/rule/remove", Map.of("id", 1), null).getStatusCode().value())
			.isEqualTo(401);

		// 低权（零权限角色）→ 403（list/edit 码分别拦截）
		assertThat(get("/system/track/visual/rule/page?appKey=" + APP_V, lowToken).getStatusCode().value())
			.isEqualTo(403);
		assertThat(get("/system/track/visual/drafts?token=" + token, lowToken).getStatusCode().value())
			.isEqualTo(403);
		assertThat(post("/system/track/visual/token", Map.of("appKey", APP_V), lowToken).getStatusCode().value())
			.isEqualTo(403);
		assertThat(post("/system/track/visual/rule/submit", Map.of("id", 1), lowToken).getStatusCode().value())
			.isEqualTo(403);
		assertThat(post("/system/track/visual/rule/remove", Map.of("id", 1), lowToken).getStatusCode().value())
			.isEqualTo(403);

		// 他租户签发：B 管理员对 A（平台租户）应用 → 400 应用不存在或无权访问
		JsonNode crossIssue = readBody(post("/system/track/visual/token", Map.of("appKey", APP_V), tenantBToken));
		assertThat(crossIssue.path("code").asInt()).isEqualTo(400);
		assertThat(crossIssue.path("msg").asText()).contains("应用不存在或无权访问");

		// 他租户操作令牌草稿：B 持 A 令牌 → 400（drafts/confirm/discard 同前置）
		assertThat(readBody(get("/system/track/visual/drafts?token=" + token, tenantBToken)).path("code").asInt())
			.isEqualTo(400);
		assertThat(readBody(post("/system/track/visual/drafts/confirm",
			Map.of("token", token, "draftId", draftId), tenantBToken)).path("code").asInt()).isEqualTo(400);
		assertThat(readBody(post("/system/track/visual/drafts/discard",
			Map.of("token", token, "draftId", draftId), tenantBToken)).path("code").asInt()).isEqualTo(400);

		// 跨租户按 id 攻击：A 确认成规则后，B 编辑/删除该 id → 400（Flex 插件隔离命中「不存在」）
		JsonNode rule = readBody(post("/system/track/visual/drafts/confirm",
			Map.of("token", token, "draftId", draftId), adminToken)).path("data");
		long ruleId = rule.path("id").asLong();
		assertThat(ruleId).isGreaterThan(0);
		assertThat(readBody(post("/system/track/visual/rule/submit",
			Map.of("id", ruleId, "status", 0), tenantBToken)).path("code").asInt())
			.as("跨租户编辑应 400").isEqualTo(400);
		assertThat(readBody(post("/system/track/visual/rule/remove",
			Map.of("id", ruleId), tenantBToken)).path("code").asInt())
			.as("跨租户删除应 400").isEqualTo(400);
		Row alive = trackRow("SELECT status, is_deleted FROM track_visual_rule WHERE id = ?", ruleId);
		assertThat(alive.getInt("status")).as("跨租户攻击不应生效").isEqualTo(1);
		assertThat(alive.getInt("is_deleted")).isEqualTo(0);

		// 分页隔离：B 查 A 应用规则 → 0 行；B 应用正向签发+确认闭环（规则归属 B 租户）
		assertThat(readBody(get("/system/track/visual/rule/page?appKey=" + APP_V, tenantBToken))
			.path("data").path("totalRow").asLong()).isEqualTo(0L);
		String bToken = issueToken(tenantBToken, APP_V_B);
		Map<Object, Object> bHash = redis.opsForHash().entries(TrackConstants.VISUAL_TOKEN_KEY_PREFIX + bToken);
		assertThat(bHash.get("tenantId")).as("B 应用令牌归属 B 租户").isEqualTo(tenantBCode);
		Map<String, Object> bDraft = new HashMap<>();
		bDraft.put("token", bToken);
		bDraft.put("event_name", "b_evt");
		bDraft.put("selector", "div.b");
		assertThat(post("/track/visual/draft", bDraft, null).getStatusCode().value()).isEqualTo(200);
		String bDraftId = readBody(get("/system/track/visual/drafts?token=" + bToken, tenantBToken))
			.path("data").get(0).path("draftId").asText();
		JsonNode bRule = readBody(post("/system/track/visual/drafts/confirm",
			Map.of("token", bToken, "draftId", bDraftId), tenantBToken)).path("data");
		assertThat(bRule.path("tenantId").asText()).as("B 规则归属 B 租户").isEqualTo(tenantBCode);
		assertThat(readBody(get("/system/track/visual/rule/page?appKey=" + APP_V_B, tenantBToken))
			.path("data").path("totalRow").asLong()).as("B 本租户规则可见").isGreaterThanOrEqualTo(1L);
	}

	// ==================== 规则 CRUD 与下发 ====================

	/** ⑤ 规则编辑：非法名 400 / selector 只读 / 改名同步事件定义 / 路由·文本改与清空 / 停用即不下发 /
	 *  撞自然键 400 / 删除后 config 不再下发且分页不可见 / 不存在 id 400 */
	@Test
	void ruleSubmitAndRemove() {
		String token = issueToken(adminToken, APP_V);
		long ruleId = confirmOne(token, "rule_edit", "section > p", null, null);

		// 非法事件名 → 400
		assertThat(readBody(post("/system/track/visual/rule/submit",
			Map.of("id", ruleId, "eventName", "$bad"), adminToken)).path("code").asInt()).isEqualTo(400);

		// selector 只读：入参忽略，DB 不变
		assertThat(readBody(post("/system/track/visual/rule/submit",
			Map.of("id", ruleId, "selector", "hacked > x"), adminToken)).path("code").asInt()).isEqualTo(200);
		assertThat(trackRow("SELECT selector FROM track_visual_rule WHERE id = ?", ruleId).getString("selector"))
			.as("selector 只读不可改").isEqualTo("section > p");

		// 改名 → 200 + track_event_def 同步（圈选创建）
		assertThat(readBody(post("/system/track/visual/rule/submit",
			Map.of("id", ruleId, "eventName", "rule_renamed"), adminToken)).path("code").asInt()).isEqualTo(200);
		assertThat(trackRow("SELECT event_name FROM track_visual_rule WHERE id = ?", ruleId).getString("event_name"))
			.isEqualTo("rule_renamed");
		Row def = trackRow("SELECT display_name, description, status FROM track_event_def"
			+ " WHERE app_key = ? AND event_name = ?", APP_V, "rule_renamed");
		assertThat(def).as("改名同步事件定义").isNotNull();
		assertThat(def.getString("description")).isEqualTo("圈选创建");

		// 路由/文本修改 + 空串清空语义（归 NULL）
		assertThat(readBody(post("/system/track/visual/rule/submit",
			Map.of("id", ruleId, "routePath", "/new", "matchText", "新文本"), adminToken)).path("code").asInt())
			.isEqualTo(200);
		Row changed = trackRow("SELECT route_path, match_text FROM track_visual_rule WHERE id = ?", ruleId);
		assertThat(changed.getString("route_path")).isEqualTo("/new");
		assertThat(changed.getString("match_text")).isEqualTo("新文本");
		Map<String, Object> clear = new HashMap<>();
		clear.put("id", ruleId);
		clear.put("routePath", "");
		clear.put("matchText", "");
		assertThat(readBody(post("/system/track/visual/rule/submit", clear, adminToken)).path("code").asInt())
			.isEqualTo(200);
		Row cleared = trackRow("SELECT id, route_path, match_text FROM track_visual_rule WHERE id = ?", ruleId);
		assertThat(cleared).as("规则行应存在").isNotNull();
		assertThat(cleared.get("route_path")).as("空串清空为 NULL").isNull();
		assertThat(cleared.get("match_text")).isNull();

		// 停用 → config 不再下发（submit evict 后首轮拉取即生效）；启用 → 恢复下发
		assertThat(readBody(post("/system/track/visual/rule/submit",
			Map.of("id", ruleId, "status", 0), adminToken)).path("code").asInt()).isEqualTo(200);
		assertThat(configHasRule(APP_V, "rule_renamed")).as("停用规则不应下发").isFalse();
		assertThat(readBody(post("/system/track/visual/rule/submit",
			Map.of("id", ruleId, "status", 1), adminToken)).path("code").asInt()).isEqualTo(200);
		assertThat(configHasRule(APP_V, "rule_renamed")).as("启用规则应恢复下发").isTrue();

		// 撞自然键 400：同 selector 另一规则存在，改名撞其 (app_key, event_name, selector, '', '')
		long colA = confirmOne(token, "col_a", "div.col", null, null);
		long colB = confirmOne(token, "col_b", "div.col", null, null);
		assertThat(colA).isNotEqualTo(colB);
		JsonNode dup = readBody(post("/system/track/visual/rule/submit",
			Map.of("id", colB, "eventName", "col_a"), adminToken));
		assertThat(dup.path("code").asInt()).isEqualTo(400);
		assertThat(dup.path("msg").asText()).contains("规则已存在");

		// 不存在 id → 400
		assertThat(readBody(post("/system/track/visual/rule/submit",
			Map.of("id", IdUtil.getSnowflakeNextId(), "status", 0), adminToken)).path("code").asInt())
			.isEqualTo(400);

		// 删除 → 逻辑删 + config 不再下发 + 分页不可见；再删 → 400
		assertThat(readBody(post("/system/track/visual/rule/remove",
			Map.of("id", ruleId), adminToken)).path("code").asInt()).isEqualTo(200);
		assertThat(trackRow("SELECT is_deleted FROM track_visual_rule WHERE id = ?", ruleId).getInt("is_deleted"))
			.isEqualTo(1);
		assertThat(configHasRule(APP_V, "rule_renamed")).as("删除后 config 不再下发").isFalse();
		JsonNode page = readBody(get("/system/track/visual/rule/page?appKey=" + APP_V + "&pageSize=100", adminToken))
			.path("data");
		for (JsonNode row : page.path("records")) {
			assertThat(row.path("id").asLong()).as("已删规则分页不可见").isNotEqualTo(ruleId);
		}
		assertThat(readBody(post("/system/track/visual/rule/remove",
			Map.of("id", ruleId), adminToken)).path("code").asInt()).as("重复删除 400").isEqualTo(400);
	}

	// ---------- 测试工具 ----------

	/** 管理端签发令牌（断言 200 并登记清理），返回 token */
	private String issueToken(String authToken, String appKey) {
		JsonNode resp = readBody(post("/system/track/visual/token", Map.of("appKey", appKey), authToken));
		assertThat(resp.path("code").asInt()).as("签发令牌：" + resp.path("msg").asText()).isEqualTo(200);
		String token = resp.path("data").path("token").asText();
		issuedTokens.add(token);
		return token;
	}

	/** 经完整 API 闭环确认一条规则（令牌→草稿→confirm），返回规则 id */
	private long confirmOne(String token, String eventName, String selector, String routePath, String matchText) {
		Map<String, Object> draft = new HashMap<>();
		draft.put("token", token);
		draft.put("event_name", eventName);
		draft.put("selector", selector);
		if (routePath != null) {
			draft.put("route_path", routePath);
		}
		if (matchText != null) {
			draft.put("match_text", matchText);
		}
		assertThat(post("/track/visual/draft", draft, null).getStatusCode().value()).isEqualTo(200);
		JsonNode drafts = readBody(get("/system/track/visual/drafts?token=" + token, adminToken)).path("data");
		String draftId = null;
		for (JsonNode d : drafts) {
			if (eventName.equals(d.path("eventName").asText()) && selector.equals(d.path("selector").asText())) {
				draftId = d.path("draftId").asText();
			}
		}
		assertThat(draftId).as("草稿应存在: " + eventName).isNotNull();
		JsonNode rule = readBody(post("/system/track/visual/drafts/confirm",
			Map.of("token", token, "draftId", draftId), adminToken));
		assertThat(rule.path("code").asInt()).as("确认草稿：" + rule.path("msg").asText()).isEqualTo(200);
		return rule.path("data").path("id").asLong();
	}

	/** /track/config 匿名拉取，visualRules 是否含指定事件名的规则 */
	private boolean configHasRule(String appKey, String eventName) {
		JsonNode rules = readBody(get("/track/config?app_key=" + appKey, null)).path("data").path("visualRules");
		for (JsonNode r : rules) {
			if (eventName.equals(r.path("event").asText())) {
				return true;
			}
		}
		return false;
	}

	/** 播种测试应用（幂等） */
	private void seedApp(String appKey, String tenantId) {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(
			"INSERT INTO track_app (id, app_key, app_name, platform, tenant_id, sample_rate, enabled,"
				+ " replay_enabled, replay_sample_rate, replay_retention_days, create_time, update_time, is_deleted)"
				+ " VALUES (?, ?, ?, 'web', ?, 100, 1, 1, 10, 14, now(), now(), 0)"
				+ " ON CONFLICT (app_key) WHERE is_deleted = 0 DO UPDATE SET"
				+ " tenant_id = EXCLUDED.tenant_id, enabled = 1",
			IdUtil.getSnowflakeNextId(), appKey, "圈选测试-" + appKey, tenantId));
	}

	/** 建零权限角色 + 低权用户并授权（TrackReplayApiTest 同款），返回其 token */
	private String createLowPrivilegeUser() {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "圈选测试零权限角色");
		role.put("roleCode", ROLE_CODE);
		role.put("sort", 99);
		role.put("dataScope", 1);
		JsonNode roleResp = readBody(post("/system/role/submit", role, adminToken));
		assertThat(roleResp.path("code").asInt()).as("建角色：" + roleResp.path("msg").asText()).isEqualTo(200);

		Map<String, Object> user = new HashMap<>();
		user.put("username", LOW_USERNAME);
		user.put("nickname", "圈选测试低权用户");
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
}
