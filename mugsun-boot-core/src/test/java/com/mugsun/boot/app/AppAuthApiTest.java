package com.mugsun.boot.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 移动端通道：滑块出题不含答案、ticket 一次性、登录内核复用、工作台聚合。
 */
class AppAuthApiTest extends AbstractIntegrationTest {

	@Autowired
	private AppSliderCaptchaService sliderCaptchaService;

	@Test
	void generateDoesNotEchoAnswer() {
		JsonNode data = readBody(get("/app/captcha/generate", null)).path("data");
		assertThat(data.path("captchaId").asText()).isNotBlank();
		assertThat(data.path("backgroundImage").asText()).startsWith("data:image/png;base64,");
		assertThat(data.path("sliderImage").asText()).startsWith("data:image/png;base64,");
		assertThat(data.has("captchaCode")).isFalse();
		assertThat(data.has("randomX")).isFalse();
		assertThat(data.has("x")).isFalse();
	}

	@Test
	void checkRejectsWrongOffset() {
		JsonNode gen = readBody(get("/app/captcha/generate", null)).path("data");
		String id = gen.path("captchaId").asText();
		Map<String, Object> body = checkBody(id, 3, 400);
		JsonNode r = readBody(post("/app/captcha/check", body, null));
		assertThat(r.path("code").asInt()).isEqualTo(400);
		assertThat(r.path("msg").asText()).contains("对齐缺口");
	}

	@Test
	void loginWithoutTicketIsRejected() {
		String publicKey = readBody(get("/app/auth/sm2-public-key", null)).path("data").path("publicKey").asText();
		Map<String, Object> body = new HashMap<>();
		body.put("username", ADMIN_USERNAME);
		body.put("password", sm2Encrypt(ADMIN_PASSWORD, publicKey));
		body.put("ticket", "not-a-ticket");
		JsonNode r = readBody(post("/app/auth/login", body, null));
		assertThat(r.path("code").asInt()).isEqualTo(400);
		assertThat(r.path("msg").asText()).contains("滑动验证");
	}

	@Test
	void homeWithoutTokenReturns401() {
		ResponseEntity<String> response = get("/app/home", null);
		assertThat(response.getStatusCode().value()).isEqualTo(401);
	}

	@Test
	void ticketIsOneTimeAndLoginReturnsHomeNick() {
		String ticket = sliderTicket();
		String publicKey = readBody(get("/app/auth/sm2-public-key", null)).path("data").path("publicKey").asText();
		Map<String, Object> body = new HashMap<>();
		body.put("username", ADMIN_USERNAME);
		body.put("password", sm2Encrypt(ADMIN_PASSWORD, publicKey));
		body.put("ticket", ticket);
		JsonNode login = readBody(post("/app/auth/login", body, null));
		assertThat(login.path("code").asInt()).as(login.path("msg").asText()).isEqualTo(200);
		String token = login.path("data").path("token").asText();
		assertThat(token).isNotBlank();

		Map<String, Object> reuse = new HashMap<>(body);
		JsonNode second = readBody(post("/app/auth/login", reuse, null));
		assertThat(second.path("code").asInt()).isEqualTo(400);

		JsonNode home = readBody(get("/app/home", token));
		assertThat(home.path("code").asInt()).isEqualTo(200);
		assertThat(home.path("data").path("user").path("userName").asText()).isEqualTo(ADMIN_USERNAME);
		assertThat(home.path("data").path("user").path("nickName").asText()).isNotBlank();
		assertThat(home.path("data").path("todos").isArray()).isTrue();

		JsonNode me = readBody(get("/app/auth/me", token));
		assertThat(me.path("data").path("userName").asText()).isEqualTo(ADMIN_USERNAME);
	}

	private String sliderTicket() {
		JsonNode gen = readBody(get("/app/captcha/generate", null)).path("data");
		String id = gen.path("captchaId").asText();
		int x = sliderCaptchaService.peekExpectedX(id);
		JsonNode check = readBody(post("/app/captcha/check", checkBody(id, x, 520), null));
		assertThat(check.path("code").asInt()).as(check.path("msg").asText()).isEqualTo(200);
		String ticket = check.path("data").path("ticket").asText();
		assertThat(ticket).isNotBlank();
		return ticket;
	}

	private Map<String, Object> checkBody(String captchaId, int moveX, long durationMs) {
		long start = System.currentTimeMillis() - durationMs;
		long end = System.currentTimeMillis();
		List<Map<String, Object>> tracks = new ArrayList<>();
		int steps = 8;
		for (int i = 0; i <= steps; i++) {
			Map<String, Object> p = new HashMap<>();
			p.put("x", moveX * i / steps);
			p.put("y", i);
			p.put("t", durationMs * i / steps);
			tracks.add(p);
		}
		Map<String, Object> body = new HashMap<>();
		body.put("captchaId", captchaId);
		body.put("moveX", moveX);
		body.put("startTime", start);
		body.put("endTime", end);
		body.put("tracks", tracks);
		return body;
	}
}
