package com.mugsun.boot.captcha;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import com.mugsun.boot.common.constant.AuthConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 旧数字码移除后各场景的验证码闸门：开着验证码不带 captchaToken → HTTP 200 + R.code 40013；
 * 关掉总开关 → 注册、忘记密码发码、短信发码直接放行；短信登录不再看验证码，但没有短信码仍被拒。
 */
class CaptchaSceneGateApiTest extends AbstractIntegrationTest {

	@Autowired
	private CaptchaProperties props;

	@Autowired
	private StringRedisTemplate redis;

	@BeforeEach
	void clearForgetThrottle() {
		// ForgetPasswordApiTest 等同进程用例会留下 admin 的 60s 发码节流键；测闸门前清掉，不改生产 60s
		redis.delete(AuthConstants.FORGET_THROTTLE_PREFIX + PLATFORM_TENANT + ":" + ADMIN_USERNAME);
	}

	@AfterEach
	void restore() {
		props.setEnabled(false);
	}

	private static String randomPhone() {
		return "139" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
	}

	private ResponseEntity<String> register(String username) {
		String publicKey = readBody(get("/auth/sm2-public-key", null)).path("data").path("publicKey").asText();
		Map<String, Object> body = new HashMap<>();
		body.put("username", username);
		body.put("password", sm2Encrypt("ItReg@12345", publicKey));
		return post("/auth/register", body, null);
	}

	private ResponseEntity<String> forgetCode() {
		// 类内两个用例都打 admin：每次发码前再清一次，避免用例顺序/重跑撞同号 60s 节流
		redis.delete(AuthConstants.FORGET_THROTTLE_PREFIX + PLATFORM_TENANT + ":" + ADMIN_USERNAME);
		Map<String, Object> body = new HashMap<>();
		body.put("username", ADMIN_USERNAME);
		body.put("tenantId", PLATFORM_TENANT);
		return post("/auth/forget-code", body, null);
	}

	private ResponseEntity<String> smsCode(String phone) {
		Map<String, Object> body = new HashMap<>();
		body.put("phone", phone);
		return post("/auth/sms-code", body, null);
	}

	private static void assertRequired(ResponseEntity<String> resp, JsonNode r) {
		assertThat(resp.getStatusCode().value()).as(resp.getBody()).isEqualTo(200);
		assertThat(r.path("code").asInt()).as(resp.getBody()).isEqualTo(CaptchaCodes.REQUIRED).isEqualTo(40013);
	}

	@Test
	void captchaOnWithoutTokenReturns40013() {
		props.setEnabled(true);
		ResponseEntity<String> reg = register("cap_gate_" + System.nanoTime() % 1_000_000);
		assertRequired(reg, readBody(reg));
		ResponseEntity<String> fc = forgetCode();
		assertRequired(fc, readBody(fc));
		ResponseEntity<String> sms = smsCode(randomPhone());
		assertRequired(sms, readBody(sms));
	}

	@Test
	void captchaOffLetsRegisterForgetAndSmsThrough() {
		props.setEnabled(false);
		JsonNode reg = readBody(register("cap_off_" + System.nanoTime() % 1_000_000));
		assertThat(reg.path("code").asInt()).as(reg.toString()).isEqualTo(200);
		JsonNode fc = readBody(forgetCode());
		assertThat(fc.path("code").asInt()).as(fc.toString()).isEqualTo(200);
		// 测试环境没配短信通道：过了验证码闸门后停在「短信通道未配置」，而不是 40013
		JsonNode sms = readBody(smsCode(randomPhone()));
		assertThat(sms.path("code").asInt()).as(sms.toString()).isNotEqualTo(CaptchaCodes.REQUIRED);
		assertThat(sms.path("msg").asText()).as(sms.toString()).isEqualTo("短信通道未配置");
	}

	@Test
	void smsLoginWithoutSmsCodeIsRejected() {
		for (boolean on : new boolean[] {true, false}) {
			props.setEnabled(on);
			Map<String, Object> body = new HashMap<>();
			body.put("phone", randomPhone());
			JsonNode r = readBody(post("/auth/sms-login", body, null));
			assertThat(r.path("code").asInt()).as(on + " " + r).isNotEqualTo(200).isNotEqualTo(CaptchaCodes.REQUIRED);
			assertThat(r.path("data").path("token").isMissingNode() || r.path("data").path("token").isNull()).isTrue();
			assertThat(r.path("msg").asText()).contains("验证码错误");
		}
	}
}
