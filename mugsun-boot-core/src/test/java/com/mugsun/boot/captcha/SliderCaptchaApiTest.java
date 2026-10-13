package com.mugsun.boot.captcha;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 滑块验证码 HTTP 链路（运行期切到 mugsun.captcha.type=SLIDER，Redis 存储） */
class SliderCaptchaApiTest extends AbstractIntegrationTest {

	@Autowired
	private SliderCaptchaService slider;
	@Autowired
	private StringRedisTemplate redis;
	@Autowired
	private CaptchaProperties props;

	/** 复用基座 Spring 上下文（新开上下文会与 warm-flow/Sa-Token 静态状态冲突），运行期切到 SLIDER */
	@BeforeEach
	void sliderMode() {
		props.setType("SLIDER");
	}

	@AfterEach
	void cleanCaptchaKeys() {
		props.setType("IMAGE");
		Set<String> keys = redis.keys(SliderCaptchaService.PREFIX + "*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	// ---------------------------------------------------------------- helpers

	@AfterEach
	void restoreLimits() {
		props.getRateLimit().setSmsIpPerHour(20);
		props.getRateLimit().setSmsPhonePerMinute(5);
		props.getRateLimit().setSmsIpPerMinute(30);
	}

	private JsonNode challenge(String scene, String tenantId) {
		Map<String, Object> body = new HashMap<>();
		body.put("scene", scene);
		body.put("clientId", CLIENT_WEB);
		body.put("tenantId", tenantId);
		body.put("type", "IMAGE");
		return readBody(post("/auth/captcha/challenge", body, null));
	}

	private Map<String, Object> verifyBody(String id, double x) {
		List<Map<String, Object>> track = new ArrayList<>();
		int n = 20;
		for (int i = 0; i <= n; i++) {
			double k = (double) i / n;
			Map<String, Object> p = new HashMap<>();
			p.put("x", x * (1 - Math.pow(1 - k, 3)));
			p.put("y", (i % 3) - 1);
			p.put("t", (long) (600 * k));
			track.add(p);
		}
		long end = System.currentTimeMillis();
		Map<String, Object> body = new HashMap<>();
		body.put("captchaId", id);
		body.put("inputMode", "pointer");
		body.put("bgDisplayWidth", 320);
		body.put("startTime", end - 600);
		body.put("endTime", end);
		body.put("track", track);
		body.put("answer", Map.of("x", x));
		return body;
	}

	private String token(String scene, String tenantId) {
		JsonNode c = challenge(scene, tenantId);
		assertThat(c.path("code").asInt()).as(c.path("msg").asText()).isEqualTo(200);
		String id = c.path("data").path("captchaId").asText();
		int x = slider.peekAnswerX(id);
		sleep(700);
		JsonNode v = readBody(post("/auth/captcha/verify", verifyBody(id, x), null));
		assertThat(v.path("code").asInt()).as(v.path("msg").asText()).isEqualTo(200);
		return v.path("data").path("captchaToken").asText();
	}

	private JsonNode login(String tenantId, String captchaToken, boolean legacyFields) {
		JsonNode key = readBody(get("/auth/sm2-public-key", null)).path("data");
		String pwd = key.path("gmEnabled").asBoolean() ? sm2Encrypt(ADMIN_PASSWORD, key.path("publicKey").asText()) : ADMIN_PASSWORD;
		Map<String, Object> body = new HashMap<>();
		body.put("tenantId", tenantId);
		body.put("username", ADMIN_USERNAME);
		body.put("password", pwd);
		body.put("clientId", CLIENT_WEB);
		if (captchaToken != null) {
			body.put("captchaToken", captchaToken);
		}
		if (legacyFields) {
			JsonNode img = readBody(get("/auth/captcha", null)).path("data");
			body.put("captchaUuid", img.path("captchaUuid").asText());
			body.put("captchaCode", img.path("captchaCode").asText());
		}
		return readBody(post("/auth/login", body, null));
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// ---------------------------------------------------------------- tests

	@Test
	void configReportsSliderAndChallengeIgnoresRequestedType() {
		JsonNode cfg = readBody(get("/auth/captcha/config?scene=login&clientId=web&tenantId=000000", null)).path("data");
		assertThat(cfg.path("enabled").asBoolean()).isTrue();
		assertThat(cfg.path("type").asText()).isEqualTo("SLIDER");
		JsonNode c = challenge("login", PLATFORM_TENANT).path("data");
		assertThat(c.path("type").asText()).isEqualTo("SLIDER");
		assertThat(c.has("expireAt")).isFalse();
		assertThat(c.path("expireSeconds").asLong()).isEqualTo(120);
		assertThat(c.toString()).doesNotContain("answer");
	}

	@Test
	void loginWithTokenSucceedsAndTokenIsOneTime() {
		String t = token("login", PLATFORM_TENANT);
		JsonNode ok = login(PLATFORM_TENANT, t, false);
		assertThat(ok.path("code").asInt()).as(ok.path("msg").asText()).isEqualTo(200);
		JsonNode replay = login(PLATFORM_TENANT, t, false);
		assertThat(replay.path("code").asInt()).isEqualTo(CaptchaCodes.REQUIRED);
	}

	@Test
	void legacyImageFieldsCannotDowngrade() {
		JsonNode r = login(PLATFORM_TENANT, null, true);
		assertThat(r.path("code").asInt()).isEqualTo(CaptchaCodes.REQUIRED);
	}

	@Test
	void crossTenantAndCrossSceneTokensAreRejected() {
		String forOther = token("login", "100999");
		assertThat(login(PLATFORM_TENANT, forOther, false).path("code").asInt()).isEqualTo(CaptchaCodes.REQUIRED);
		String forRegister = token("register", PLATFORM_TENANT);
		assertThat(login(PLATFORM_TENANT, forRegister, false).path("code").asInt()).isEqualTo(CaptchaCodes.REQUIRED);
	}

	@Test
	void smsSendRequiresToken() {
		Map<String, Object> body = new HashMap<>();
		body.put("phone", "13800000000");
		assertThat(readBody(post("/auth/sms-code", body, null)).path("code").asInt()).isEqualTo(CaptchaCodes.REQUIRED);
	}

	@Test
	void smsIpBurstLimitAppliesToRequestsWithoutToken() {
		props.getRateLimit().setSmsIpPerMinute(5);
		Map<String, Object> body = new HashMap<>();
		body.put("phone", "13800000023");
		for (int i = 0; i < 5; i++) {
			assertThat(readBody(post("/auth/sms-code", body, null)).path("code").asInt()).isEqualTo(CaptchaCodes.REQUIRED);
		}
		JsonNode r = readBody(post("/auth/sms-code", body, null));
		assertThat(r.path("code").asInt()).isEqualTo(CaptchaCodes.RATE_LIMITED);
		assertThat(r.path("data").path("retryAfter").asLong()).isBetween(1L, 60L);
		assertThat(slider.smsPhoneCount("13800000023")).isZero();
		assertThat(slider.smsBurstPhoneCount("13800000023")).isZero();
	}

	@Test
	void smsRequestsWithoutTokenDoNotCountTowardPhoneBurst() {
		// B4：攻击者从不同 IP 发无 token / 无效 token 请求，不能把受害号码的手机号短窗打满
		String phone = "13800000024";
		props.getRateLimit().setSmsIpPerMinute(100);
		for (int i = 0; i < 12; i++) {
			Map<String, Object> body = new HashMap<>();
			body.put("phone", phone);
			if (i % 2 == 1) {
				body.put("captchaToken", "0123456789abcdef0123456789abcdef");
			}
			assertThat(readBody(post("/auth/sms-code", body, null)).path("code").asInt()).isEqualTo(CaptchaCodes.REQUIRED);
		}
		assertThat(slider.smsBurstPhoneCount(phone)).isZero();
		// 受害者本人带合法 token 仍能通过短窗
		Map<String, Object> body = new HashMap<>();
		body.put("phone", phone);
		body.put("captchaToken", token("sms", PLATFORM_TENANT));
		assertThat(readBody(post("/auth/sms-code", body, null)).path("code").asInt()).isNotEqualTo(CaptchaCodes.RATE_LIMITED);
		assertThat(slider.smsBurstPhoneCount(phone)).isEqualTo(1);
		assertThat(slider.smsPhoneCount(phone)).isEqualTo(1);
	}

	@Test
	void smsCodeWithoutValidTokenDoesNotConsumeQuota() {
		String phone = "13800000021";
		props.getRateLimit().setSmsPhonePerMinute(100);
		for (int i = 0; i < 15; i++) {
			Map<String, Object> body = new HashMap<>();
			body.put("phone", phone);
			if (i % 2 == 1) {
				body.put("captchaToken", "0123456789abcdef0123456789abcdef");
			}
			assertThat(readBody(post("/auth/sms-code", body, null)).path("code").asInt()).isEqualTo(CaptchaCodes.REQUIRED);
		}
		assertThat(slider.smsPhoneCount(phone)).isZero();
		// 合法请求仍可通过配额（测试环境短信通道未配置，发送失败不退计数）
		Map<String, Object> body = new HashMap<>();
		body.put("phone", phone);
		body.put("captchaToken", token("sms", PLATFORM_TENANT));
		assertThat(readBody(post("/auth/sms-code", body, null)).path("code").asInt()).isNotEqualTo(CaptchaCodes.RATE_LIMITED);
		assertThat(slider.smsPhoneCount(phone)).isEqualTo(1);
	}

	@Test
	void concurrentLegitSmsCodeAtMostTenPassQuota() throws Exception {
		String phone = "13800000022";
		props.getRateLimit().setSmsPhonePerMinute(100);
		List<String> tokens = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			tokens.add(token("sms", PLATFORM_TENANT));
		}
		ExecutorService pool = Executors.newFixedThreadPool(20);
		CountDownLatch go = new CountDownLatch(1);
		AtomicInteger limited = new AtomicInteger();
		List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
		for (String t : tokens) {
			fs.add(pool.submit(() -> {
				go.await();
				Map<String, Object> body = new HashMap<>();
				body.put("phone", phone);
				body.put("captchaToken", t);
				if (readBody(post("/auth/sms-code", body, null)).path("code").asInt() == CaptchaCodes.RATE_LIMITED) {
					limited.incrementAndGet();
				}
				return null;
			}));
		}
		go.countDown();
		for (java.util.concurrent.Future<?> f : fs) {
			f.get(30, TimeUnit.SECONDS);
		}
		pool.shutdown();
		assertThat(slider.smsPhoneCount(phone)).isEqualTo(10);
		assertThat(limited.get()).isEqualTo(10);
	}

	@Test
	void concurrentVerifyOnlyOneWins() throws Exception {
		JsonNode c = challenge("login", PLATFORM_TENANT);
		String id = c.path("data").path("captchaId").asText();
		int x = slider.peekAnswerX(id);
		sleep(700);
		Map<String, Object> body = verifyBody(id, x);
		AtomicInteger ok = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(8);
		CountDownLatch go = new CountDownLatch(1);
		for (int i = 0; i < 8; i++) {
			pool.submit(() -> {
				go.await();
				if (readBody(post("/auth/captcha/verify", body, null)).path("code").asInt() == 200) {
					ok.incrementAndGet();
				}
				return null;
			});
		}
		go.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		assertThat(ok.get()).isEqualTo(1);
	}

	@Test
	void forgedXffDoesNotEscapeRateLimit() {
		for (int i = 0; i < 5; i++) {
			String id = challenge("login", PLATFORM_TENANT).path("data").path("captchaId").asText();
			HttpHeaders h = new HttpHeaders();
			h.setContentType(MediaType.APPLICATION_JSON);
			h.add("X-Forwarded-For", "198.51.100." + (i + 10));
			JsonNode r = readBody(rest.exchange("/auth/captcha/verify", HttpMethod.POST,
				new HttpEntity<>(verifyBody(id, 1), h), String.class));
			// 伪造 XFF 仍按真实 IP 计数：第 5 次失败即锁定，直接 40012
			assertThat(r.path("code").asInt()).isEqualTo(i < 4 ? CaptchaCodes.FAILED : CaptchaCodes.RATE_LIMITED);
		}
		HttpHeaders h = new HttpHeaders();
		h.setContentType(MediaType.APPLICATION_JSON);
		h.add("X-Forwarded-For", "198.51.100.200");
		Map<String, Object> body = Map.of("scene", "login", "clientId", CLIENT_WEB, "tenantId", PLATFORM_TENANT);
		JsonNode limited = readBody(rest.exchange("/auth/captcha/challenge", HttpMethod.POST, new HttpEntity<>(body, h), String.class));
		assertThat(limited.path("code").asInt()).isEqualTo(CaptchaCodes.RATE_LIMITED);
		assertThat(limited.path("data").path("retryAfter").asLong()).isPositive();
	}

	@Test
	void tenantSwitchOffSkipsOnlyThatTenant() {
		String admin = loginAdminWithSlider();
		Map<String, Object> tenant = new HashMap<>();
		tenant.put("tenantName", "验证码关闭测试" + System.nanoTime() % 100000);
		JsonNode created = readBody(post("/system/tenant/create", tenant, admin));
		assertThat(created.path("code").asInt()).as(created.path("msg").asText()).isEqualTo(200);
		String code = created.path("data").asText();
		JsonNode list = readBody(get("/system/tenant/list?tenantCode=" + code, admin)).path("data");
		long id = list.get(0).path("id").asLong();

		JsonNode off = readBody(post("/system/tenant/captcha", Map.of("id", id, "enabled", false), admin));
		assertThat(off.path("code").asInt()).as(off.path("msg").asText()).isEqualTo(200);

		JsonNode cfg = readBody(get("/auth/captcha/config?scene=login&clientId=web&tenantId=" + code, null)).path("data");
		assertThat(cfg.path("enabled").asBoolean()).isFalse();
		assertThat(challenge("login", code).path("code").asInt()).isEqualTo(CaptchaCodes.DISABLED);
		// 关闭租户：不带 token 进入账号密码校验（admin 在该租户密码不同 → 普通失败，而非 40013）
		assertThat(login(code, null, false).path("code").asInt()).isNotEqualTo(CaptchaCodes.REQUIRED);
		// 其他租户仍须带 token
		assertThat(login(PLATFORM_TENANT, null, false).path("code").asInt()).isEqualTo(CaptchaCodes.REQUIRED);

		JsonNode on = readBody(post("/system/tenant/captcha", Map.of("id", id, "enabled", true), admin));
		assertThat(on.path("code").asInt()).isEqualTo(200);
		assertThat(login(code, null, false).path("code").asInt()).isEqualTo(CaptchaCodes.REQUIRED);
	}

	@Test
	void tenantSwitchRequiresLogin() {
		JsonNode r = readBody(post("/system/tenant/captcha", Map.of("id", 1, "enabled", false), null));
		assertThat(r.path("code").asInt()).isNotEqualTo(200);
	}

	private String loginAdminWithSlider() {
		JsonNode r = login(PLATFORM_TENANT, token("login", PLATFORM_TENANT), false);
		assertThat(r.path("code").asInt()).as(r.path("msg").asText()).isEqualTo(200);
		return r.path("data").path("token").asText();
	}

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbc;

	private String[] createTenant(String admin, Map<String, Object> extra) {
		Map<String, Object> tenant = new HashMap<>(extra);
		tenant.put("tenantName", "验证码测试" + System.nanoTime() % 1000000);
		JsonNode created = readBody(post("/system/tenant/create", tenant, admin));
		assertThat(created.path("code").asInt()).as(created.path("msg").asText()).isEqualTo(200);
		String code = created.path("data").asText();
		long id = readBody(get("/system/tenant/list?tenantCode=" + code, admin)).path("data").get(0).path("id").asLong();
		return new String[]{code, String.valueOf(id)};
	}

	@Test
	void smsSendIgnoresClientTenantSwitch() {
		String admin = loginAdminWithSlider();
		String[] t = createTenant(admin, Map.of());
		JsonNode off = readBody(post("/system/tenant/captcha", Map.of("id", Long.parseLong(t[1]), "enabled", false), admin));
		assertThat(off.path("code").asInt()).isEqualTo(200);
		// 填一个关闭了验证码的租户，发短信仍须带 token
		Map<String, Object> body = new HashMap<>();
		body.put("phone", "13800000009");
		body.put("tenantId", t[0]);
		assertThat(readBody(post("/auth/sms-code", body, null)).path("code").asInt()).isEqualTo(CaptchaCodes.REQUIRED);
		JsonNode cfg = readBody(get("/auth/captcha/config?scene=sms&tenantId=" + t[0], null)).path("data");
		assertThat(cfg.path("enabled").asBoolean()).isTrue();
	}

	@Test
	void clientWithCaptchaOffCannotBypassLogin() {
		jdbc.update("INSERT INTO sys_client (id, client_id, client_name, captcha_enabled, max_online, token_timeout, status, create_time, is_deleted) "
			+ "VALUES (9917000000000001, 'nocap', '无验证码客户端', 0, 0, 2592000, 1, now(), 0) ON CONFLICT (id) DO NOTHING");
		try {
			JsonNode key = readBody(get("/auth/sm2-public-key", null)).path("data");
			String pwd = key.path("gmEnabled").asBoolean() ? sm2Encrypt(ADMIN_PASSWORD, key.path("publicKey").asText()) : ADMIN_PASSWORD;
			Map<String, Object> body = new HashMap<>();
			body.put("tenantId", PLATFORM_TENANT);
			body.put("username", ADMIN_USERNAME);
			body.put("password", pwd);
			body.put("clientId", "nocap");
			assertThat(readBody(post("/auth/login", body, null)).path("code").asInt()).isEqualTo(CaptchaCodes.REQUIRED);
			JsonNode cfg = readBody(get("/auth/captcha/config?scene=login&clientId=nocap&tenantId=000000", null)).path("data");
			assertThat(cfg.path("enabled").asBoolean()).isTrue();
		} finally {
			jdbc.update("DELETE FROM sys_client WHERE id = 9917000000000001");
		}
	}

	@Test
	void tenantCreateCannotDisableCaptcha() {
		String admin = loginAdminWithSlider();
		String[] t = createTenant(admin, Map.of("captchaEnabled", 0));
		JsonNode cfg = readBody(get("/auth/captcha/config?scene=login&clientId=web&tenantId=" + t[0], null)).path("data");
		assertThat(cfg.path("enabled").asBoolean()).isTrue();
	}

	@Test
	void keyboardChallengeReturnsStepAndModeIsBound() {
		Map<String, Object> req = new HashMap<>();
		req.put("scene", "login");
		req.put("clientId", CLIENT_WEB);
		req.put("tenantId", PLATFORM_TENANT);
		req.put("inputMode", "keyboard");
		JsonNode c = readBody(post("/auth/captcha/challenge", req, null)).path("data");
		assertThat(c.path("inputMode").asText()).isEqualTo("keyboard");
		assertThat(c.path("keyboardStep").asInt()).isEqualTo(4);
		// pointer 出题后按 keyboard 提交 → 40011
		JsonNode p = challenge("login", PLATFORM_TENANT).path("data");
		String id = p.path("captchaId").asText();
		int x = slider.peekAnswerX(id);
		sleep(700);
		long end = System.currentTimeMillis();
		Map<String, Object> body = new HashMap<>();
		body.put("captchaId", id);
		body.put("inputMode", "keyboard");
		body.put("bgDisplayWidth", 320);
		body.put("startTime", end - 650);
		body.put("endTime", end);
		body.put("answer", Map.of("x", x));
		body.put("keyPresses", Math.round(x / 4f));
		assertThat(readBody(post("/auth/captcha/verify", body, null)).path("code").asInt()).isEqualTo(CaptchaCodes.FAILED);
	}

	@Test
	void chunkedOversizedBodyIsRejected() throws Exception {
		java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
			new java.net.URI("http://localhost:" + port + "/auth/captcha/verify").toURL().openConnection();
		conn.setRequestMethod("POST");
		conn.setDoOutput(true);
		conn.setChunkedStreamingMode(4096);
		conn.setRequestProperty("Content-Type", "application/json");
		StringBuilder sb = new StringBuilder("{\"captchaId\":\"0123456789abcdef0123456789abcdef\",\"track\":[");
		for (int i = 0; i < 4000; i++) {
			sb.append(i == 0 ? "" : ",").append("{\"x\":1,\"y\":1,\"t\":").append(i).append('}');
		}
		sb.append("]}");
		byte[] bytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
		assertThat(bytes.length).isGreaterThan(64 * 1024);
		int status;
		String resp;
		try (java.io.OutputStream out = conn.getOutputStream()) {
			out.write(bytes);
		} catch (java.io.IOException ignored) {
			// 服务端提前断开同样视为拒绝
		}
		status = conn.getResponseCode();
		java.io.InputStream in = status < 400 ? conn.getInputStream() : conn.getErrorStream();
		resp = in == null ? "" : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		boolean rejected = status >= 400 || !resp.contains("\"code\":200");
		if (status == 200 && !resp.isEmpty()) {
			// 能读到响应时必须是标准 R JSON 40011
			assertThat(resp).contains("\"code\":" + CaptchaCodes.FAILED);
		}
		assertThat(rejected).as("status=" + status + " body=" + resp).isTrue();
		assertThat(resp).doesNotContain("captchaToken");
	}
}
