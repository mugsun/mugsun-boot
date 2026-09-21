package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 忘记密码链路回归：发码的通道显式降级 + 重置全链路（注码→重置→新密码可登录）。
 * 测试环境 SMTP 为占位符：发码接口须如实报错（不再假成功）；邮件码经 Redis 直注绕开通道跳验证重置段。
 */
class ForgetPasswordApiTest extends AbstractIntegrationTest {

	@Autowired
	private StringRedisTemplate redis;

	@Test
	void forgetCodeFailsExplicitlyWhenMailNotConfigured() {
		JsonNode captcha = readBody(get("/auth/captcha", null));
		Map<String, Object> body = new HashMap<>();
		body.put("username", ADMIN_USERNAME);
		body.put("captchaUuid", captcha.path("data").path("captchaUuid").asText());
		body.put("captchaCode", captcha.path("data").path("captchaCode").asText());
		ResponseEntity<String> response = post("/auth/forget-code", body, null);
		JsonNode r = readBody(response);
		assertThat(r.path("code").asInt()).isEqualTo(400);
		assertThat(r.path("msg").asText()).contains("邮件通道未配置");
	}

	@Test
	void forgetResetFullChain() {
		// 建带邮箱的独立账号
		String adminToken = loginAdmin();
		String username = "it-forget-" + System.currentTimeMillis() % 100000;
		Map<String, Object> user = new HashMap<>();
		user.put("username", username);
		user.put("nickname", "忘记密码验证");
		user.put("password", "ItForget@123");
		user.put("email", "forget@test.com");
		user.put("status", 1);
		JsonNode createResp = readBody(post("/system/user/submit", user, adminToken));
		assertThat(createResp.path("code").asInt()).as("建用户：" + createResp.path("msg").asText()).isEqualTo(200);

		// 直注邮件码（绕开 SMTP 跳；键设计与 AuthConstants 一致）
		String codeKey = "mugsun:forget:code:" + PLATFORM_TENANT + ":" + username;
		redis.opsForValue().set(codeKey, "654321", Duration.ofMinutes(5));

		// 重置（SM2 密文新密码）
		String publicKey = readBody(get("/auth/sm2-public-key", null)).path("data").path("publicKey").asText();
		Map<String, Object> body = new HashMap<>();
		body.put("username", username);
		body.put("code", "654321");
		body.put("newPassword", sm2Encrypt("ItForget@456", publicKey));
		ResponseEntity<String> resetResp = post("/auth/forget-reset", body, null);
		JsonNode reset = readBody(resetResp);
		assertThat(reset.path("code").asInt()).as("重置须成功：" + reset.path("msg").asText()).isEqualTo(200);

		// 邮件码一次性烧毁 + 旧密码失效 + 新密码可登录
		assertThat(redis.opsForValue().get(codeKey)).as("邮件码须即焚").isNull();
		String newToken = login(PLATFORM_TENANT, username, "ItForget@456");
		assertThat(newToken).isNotBlank();

		// 错误码被拒
		redis.opsForValue().set(codeKey, "111111", Duration.ofMinutes(5));
		Map<String, Object> badBody = new HashMap<>();
		badBody.put("username", username);
		badBody.put("code", "000000");
		badBody.put("newPassword", sm2Encrypt("ItForget@789", publicKey));
		JsonNode bad = readBody(post("/auth/forget-reset", badBody, null));
		assertThat(bad.path("code").asInt()).as("错误邮件码须被拒").isEqualTo(400);
	}
}
