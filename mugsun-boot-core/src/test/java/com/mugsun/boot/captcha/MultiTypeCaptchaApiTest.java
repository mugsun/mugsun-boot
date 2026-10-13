package com.mugsun.boot.captcha;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 多类型验证码 HTTP：随机只出启用类型、keyboard 回落、config RANDOM、端到端校验 */
class MultiTypeCaptchaApiTest extends AbstractIntegrationTest {

	@Autowired
	private SliderCaptchaService slider;
	@Autowired
	private StringRedisTemplate redis;
	@Autowired
	private CaptchaProperties props;

	@BeforeEach
	void multiMode() {
		props.setType("SLIDER");
	}

	@AfterEach
	void restore() {
		props.setType("IMAGE");
		props.setDefaultTypes(new ArrayList<>(List.of("SLIDER")));
		Set<String> keys = redis.keys(SliderCaptchaService.PREFIX + "*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	private JsonNode challenge(String inputMode, String requestedType) {
		Map<String, Object> body = new HashMap<>();
		body.put("scene", "login");
		body.put("clientId", CLIENT_WEB);
		body.put("tenantId", PLATFORM_TENANT);
		body.put("inputMode", inputMode);
		body.put("type", requestedType);
		JsonNode r = readBody(post("/auth/captcha/challenge", body, null));
		assertThat(r.path("code").asInt()).as(r.path("msg").asText()).isEqualTo(200);
		return r.path("data");
	}

	@Test
	void randomOnlyFromEnabledTypesAndClientCannotChoose() {
		props.setDefaultTypes(new ArrayList<>(List.of("ROTATE", "JIGSAW", "WORD_CLICK")));
		JsonNode cfg = readBody(get("/auth/captcha/config?scene=login&clientId=web&tenantId=000000", null)).path("data");
		assertThat(cfg.path("type").asText()).isEqualTo("RANDOM");
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 12; i++) {
			seen.add(challenge("pointer", "SLIDER").path("type").asText());
		}
		assertThat(seen).isSubsetOf("ROTATE", "JIGSAW", "WORD_CLICK").isNotEmpty();
	}

	@Test
	void keyboardPicksOnlyKeyboardTypesOrFallsBackToSlider() {
		props.setDefaultTypes(new ArrayList<>(List.of("ROTATE", "ICON_CLICK")));
		for (int i = 0; i < 4; i++) {
			JsonNode c = challenge("keyboard", null);
			assertThat(c.path("type").asText()).isEqualTo("ROTATE");
			assertThat(c.path("keyboardStep").asInt()).isPositive();
		}
		props.setDefaultTypes(new ArrayList<>(List.of("ICON_CLICK", "JIGSAW")));
		assertThat(challenge("keyboard", "JIGSAW").path("type").asText()).isEqualTo("SLIDER");
	}

	@Test
	void singleTypeConfigAndJigsawEndToEnd() throws Exception {
		props.setDefaultTypes(new ArrayList<>(List.of("JIGSAW")));
		JsonNode cfg = readBody(get("/auth/captcha/config?scene=login&clientId=web&tenantId=000000", null)).path("data");
		assertThat(cfg.path("type").asText()).isEqualTo("JIGSAW");
		JsonNode c = challenge("pointer", null);
		assertThat(c.path("type").asText()).isEqualTo("JIGSAW");
		assertThat(c.path("tiles").size()).isEqualTo(4);
		assertThat(c.toString()).doesNotContain("answer");
		String id = c.path("captchaId").asText();
		List<Integer> order = new ArrayList<>();
		for (String s : slider.peekAnswer(id)[0].split(",")) {
			order.add(Integer.parseInt(s));
		}
		Thread.sleep(600);
		long end = System.currentTimeMillis();
		Map<String, Object> body = new HashMap<>();
		body.put("captchaId", id);
		body.put("inputMode", "pointer");
		body.put("bgDisplayWidth", 320);
		body.put("startTime", end - 500);
		body.put("endTime", end);
		body.put("answer", Map.of("order", order));
		JsonNode v = readBody(post("/auth/captcha/verify", body, null));
		assertThat(v.path("code").asInt()).as(v.path("msg").asText()).isEqualTo(200);
		assertThat(v.path("data").path("captchaToken").asText()).hasSize(32);
		JsonNode replay = readBody(post("/auth/captcha/verify", body, null));
		assertThat(replay.path("code").asInt()).isEqualTo(CaptchaCodes.EXPIRED);
	}
}
