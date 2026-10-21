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

/** 多类型验证码 HTTP：随机只出启用类型、keyboard 无交集 40015、config RANDOM、端到端校验 */
class MultiTypeCaptchaApiTest extends AbstractIntegrationTest {

	@Autowired
	private SliderCaptchaService slider;
	@Autowired
	private StringRedisTemplate redis;
	@Autowired
	private CaptchaProperties props;

	private int savedPerMinute;

	@BeforeEach
	void multiMode() {
		props.setEnabled(true);
		// 本类要连续取几十道题验证随机分布，临时放宽单 IP 每分钟取题上限（限流另有专门测试）
		savedPerMinute = props.getRateLimit().getChallengePerMinute();
		props.getRateLimit().setChallengePerMinute(1000);
	}

	@AfterEach
	void restore() {
		props.setEnabled(false);
		props.getRateLimit().setChallengePerMinute(savedPerMinute);
		props.setDefaultTypes(new ArrayList<>(CaptchaProperties.PLATFORM_DEFAULT_TYPES));
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
	void platformDefaultIsSliderPlusWordClick() {
		// application.yml 的平台默认：SLIDER,WORD_CLICK；旋转 / 图标点选默认关
		assertThat(props.getDefaultTypes()).containsExactly("SLIDER", "WORD_CLICK");
		JsonNode cfg = readBody(get("/auth/captcha/config?scene=login&clientId=web&tenantId=000000", null)).path("data");
		assertThat(cfg.path("enabled").asBoolean()).isTrue();
		assertThat(cfg.path("type").asText()).isEqualTo("RANDOM");
		Set<String> seen = new HashSet<>();
		// 默认就是多题型随机（RANDOM）：40 次里两种都要出现（漏掉一种的概率约 2^-39），且客户端点名 ROTATE 无效
		for (int i = 0; i < 40; i++) {
			seen.add(challenge("pointer", "ROTATE").path("type").asText());
		}
		assertThat(seen).containsExactlyInAnyOrder("SLIDER", "WORD_CLICK");
		// 键盘只从 SLIDER/ROTATE 里选：平台默认下就是 SLIDER
		assertThat(challenge("keyboard", null).path("type").asText()).isEqualTo("SLIDER");
	}

	@Test
	void configNeverReturnsRemovedImageType() {
		// 旧数字码 IMAGE 已移除：开关开 / 关、单题型 / 多题型，config.type 都只会是启用的题型或 RANDOM
		for (boolean on : new boolean[] {true, false}) {
			props.setEnabled(on);
			for (List<String> types : List.of(List.of("SLIDER"), List.of("JIGSAW"), List.of("SLIDER", "WORD_CLICK"))) {
				props.setDefaultTypes(new ArrayList<>(types));
				for (String scene : List.of("login", "register", "sms")) {
					JsonNode cfg = readBody(get("/auth/captcha/config?scene=" + scene + "&clientId=web&tenantId=000000", null)).path("data");
					String t = cfg.path("type").asText();
					assertThat(t).as(on + " " + types + " " + scene).isNotEqualTo("IMAGE")
						.isEqualTo(types.size() > 1 ? "RANDOM" : types.get(0));
				}
			}
		}
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
	void keyboardPicksOnlyEnabledKeyboardTypesElse40015() {
		props.setDefaultTypes(new ArrayList<>(List.of("ROTATE", "ICON_CLICK")));
		for (int i = 0; i < 4; i++) {
			JsonNode c = challenge("keyboard", null);
			assertThat(c.path("type").asText()).isEqualTo("ROTATE");
			assertThat(c.path("keyboardStep").asInt()).isPositive();
		}
		props.setDefaultTypes(new ArrayList<>(List.of("ICON_CLICK", "JIGSAW")));
		Map<String, Object> body = new HashMap<>();
		body.put("scene", "login");
		body.put("clientId", CLIENT_WEB);
		body.put("tenantId", PLATFORM_TENANT);
		body.put("inputMode", "keyboard");
		body.put("type", "SLIDER");
		JsonNode r = readBody(post("/auth/captcha/challenge", body, null));
		assertThat(r.path("code").asInt()).isEqualTo(CaptchaCodes.KEYBOARD_UNAVAILABLE);
		assertThat(r.path("msg").asText()).isEqualTo("未启用无障碍验证方式");
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
