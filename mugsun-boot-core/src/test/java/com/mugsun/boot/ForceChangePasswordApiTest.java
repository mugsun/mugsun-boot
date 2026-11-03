package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #81：一次性初始口令 + 首次登录强制改密。
 * 重置/新建租户各自随机；改密前业务接口拒绝；改密后旧口令失效。
 */
class ForceChangePasswordApiTest extends AbstractIntegrationTest {

	@Test
	void tenantCreateIssuesDistinctOneTimePasswordsAndForcesChange() {
		String admin = loginAdmin();
		Set<String> issued = new HashSet<>();
		String code1 = null;
		String otp1 = null;
		for (int i = 0; i < 2; i++) {
			Map<String, Object> tenant = new HashMap<>();
			tenant.put("tenantName", "强制改密租户-" + i + "-" + System.nanoTime() % 1_000_000);
			JsonNode created = readBody(post("/system/tenant/create", tenant, admin));
			assertThat(created.path("code").asInt()).as(created.path("msg").asText()).isEqualTo(200);
			String code = tenantCodeOf(created);
			String otp = initialPasswordOf(created);
			assertThat(code).isNotBlank();
			assertThat(otp).isNotBlank().isNotEqualTo(ADMIN_PASSWORD);
			assertThat(otp).matches("^Mg[A-Za-z0-9]{4}-[A-Za-z0-9]{10}9a$");
			assertThat(issued.add(otp)).as("两次建租户口令须不同").isTrue();
			if (i == 0) {
				code1 = code;
				otp1 = otp;
			}
		}

		// 一次性口令可登录；改密前业务接口被拒；/auth/info 仍可访问
		String token = login(code1, ADMIN_USERNAME, otp1);
		JsonNode info = readBody(get("/auth/info", token));
		assertThat(info.path("code").asInt()).isEqualTo(200);
		assertThat(info.path("data").path("needChangePassword").asBoolean()).isTrue();

		JsonNode blocked = readBody(get("/system/user/page?pageNum=1&pageSize=10", token));
		assertThat(blocked.path("code").asInt()).as(blocked.path("msg").asText()).isEqualTo(400);
		assertThat(blocked.path("msg").asText()).contains("请先修改初始口令");

		// 改密
		String publicKey = readBody(get("/auth/sm2-public-key", null)).path("data").path("publicKey").asText();
		String newPwd = "ItForce@Change81";
		Map<String, Object> body = new HashMap<>();
		body.put("oldPassword", sm2Encrypt(otp1, publicKey));
		body.put("newPassword", sm2Encrypt(newPwd, publicKey));
		JsonNode changed = readBody(post("/auth/update-password", body, token));
		assertThat(changed.path("code").asInt()).as(changed.path("msg").asText()).isEqualTo(200);

		// 旧一次性口令失效；新口令可登录且业务接口放行
		ResponseEntity<String> oldLogin = post("/auth/login", Map.of(
			"tenantId", code1,
			"username", ADMIN_USERNAME,
			"password", sm2Encrypt(otp1, publicKey),
			"clientId", CLIENT_WEB), null);
		assertThat(readBody(oldLogin).path("code").asInt()).isEqualTo(400);

		String fresh = login(code1, ADMIN_USERNAME, newPwd);
		JsonNode page = readBody(get("/system/user/page?pageNum=1&pageSize=10", fresh));
		assertThat(page.path("code").asInt()).as(page.path("msg").asText()).isEqualTo(200);
		assertThat(readBody(get("/auth/info", fresh)).path("data").path("needChangePassword").asBoolean()).isFalse();
	}

	@Test
	void resetPasswordIssuesOneTimePasswordAndBlocksUntilChanged() {
		String admin = loginAdmin();
		String username = "it_otp_reset_" + System.nanoTime() % 1_000_000;
		Map<String, Object> user = new HashMap<>();
		user.put("username", username);
		user.put("nickname", "重置一次性口令");
		user.put("password", "ItCreate@12345");
		user.put("status", 1);
		JsonNode created = readBody(post("/system/user/submit", user, admin));
		assertThat(created.path("code").asInt()).as(created.path("msg").asText()).isEqualTo(200);

		long userId = readBody(get("/system/user/page?pageNum=1&pageSize=50&username=" + username, admin))
			.path("data").path("records").get(0).path("id").asLong();

		JsonNode reset1 = readBody(post("/system/user/reset-password", List.of(userId), admin));
		assertThat(reset1.path("code").asInt()).as(reset1.path("msg").asText()).isEqualTo(200);
		String otp1 = reset1.path("data").get(0).path("initialPassword").asText();
		assertThat(otp1).isNotBlank().isNotEqualTo(ADMIN_PASSWORD).isNotEqualTo("ItCreate@12345");

		JsonNode reset2 = readBody(post("/system/user/reset-password", List.of(userId), admin));
		String otp2 = reset2.path("data").get(0).path("initialPassword").asText();
		assertThat(otp2).isNotEqualTo(otp1);

		String token = login(PLATFORM_TENANT, username, otp2);
		JsonNode blocked = readBody(get("/system/user/page?pageNum=1&pageSize=5", token));
		// 无权限也可能 403；强制改密应先于权限以 400 提示改密
		assertThat(blocked.path("code").asInt()).isIn(400, 403);
		if (blocked.path("code").asInt() == 400) {
			assertThat(blocked.path("msg").asText()).contains("请先修改初始口令");
		}

		String publicKey = readBody(get("/auth/sm2-public-key", null)).path("data").path("publicKey").asText();
		String newPwd = "ItReset@Done81x";
		Map<String, Object> body = new HashMap<>();
		body.put("oldPassword", sm2Encrypt(otp2, publicKey));
		body.put("newPassword", sm2Encrypt(newPwd, publicKey));
		assertThat(readBody(post("/auth/update-password", body, token)).path("code").asInt()).isEqualTo(200);

		assertThat(readBody(post("/auth/login", Map.of(
			"tenantId", PLATFORM_TENANT,
			"username", username,
			"password", sm2Encrypt(otp2, publicKey),
			"clientId", CLIENT_WEB), null)).path("code").asInt()).isEqualTo(400);

		assertThat(login(PLATFORM_TENANT, username, newPwd)).isNotBlank();
	}

	@Test
	void createUserWithoutPasswordReturnsOneTimePassword() {
		String admin = loginAdmin();
		String username = "it_otp_create_" + System.nanoTime() % 1_000_000;
		Map<String, Object> user = new HashMap<>();
		user.put("username", username);
		user.put("nickname", "默认一次性口令");
		user.put("status", 1);
		JsonNode created = readBody(post("/system/user/submit", user, admin));
		assertThat(created.path("code").asInt()).as(created.path("msg").asText()).isEqualTo(200);
		String otp = created.path("data").path("initialPassword").asText();
		assertThat(otp).isNotBlank().matches("^Mg[A-Za-z0-9]{4}-[A-Za-z0-9]{10}9a$");
		assertThat(login(PLATFORM_TENANT, username, otp)).isNotBlank();
	}
}
