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
 * 管理员新建用户时代填的口令同样要过口令强度规则，本人首次登录也要先改密；编辑接口不接受口令。
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
		// 仅需登录的非放行接口：强制改密应 400（不依赖业务权限码）
		JsonNode blocked = readBody(post("/auth/update-avatar", Map.of("avatar", "x"), token));
		assertThat(blocked.path("code").asInt()).as(blocked.path("msg").asText()).isEqualTo(400);
		assertThat(blocked.path("msg").asText()).contains("请先修改初始口令");

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

	@Test
	void editDoesNotClearMustChangePassword() {
		String admin = loginAdmin();
		String username = "it_otp_edit_" + System.nanoTime() % 1_000_000;
		Map<String, Object> user = new HashMap<>();
		user.put("username", username);
		user.put("nickname", "编辑不清标记");
		user.put("status", 1);
		JsonNode created = readBody(post("/system/user/submit", user, admin));
		assertThat(created.path("code").asInt()).as(created.path("msg").asText()).isEqualTo(200);
		String otp = created.path("data").path("initialPassword").asText();
		assertThat(otp).isNotBlank();

		long userId = readBody(get("/system/user/page?pageNum=1&pageSize=50&username=" + username, admin))
			.path("data").path("records").get(0).path("id").asLong();

		// 编辑昵称；即使误传 mustChangePassword=0 也不得清库标记
		Map<String, Object> edit = new HashMap<>();
		edit.put("id", userId);
		edit.put("username", username);
		edit.put("nickname", "编辑后仍须改密");
		edit.put("status", 1);
		edit.put("mustChangePassword", 0);
		JsonNode saved = readBody(post("/system/user/submit", edit, admin));
		assertThat(saved.path("code").asInt()).as(saved.path("msg").asText()).isEqualTo(200);

		String token = login(PLATFORM_TENANT, username, otp);
		JsonNode blocked = readBody(post("/auth/update-avatar", Map.of("avatar", "x"), token));
		assertThat(blocked.path("code").asInt()).as(blocked.path("msg").asText()).isEqualTo(400);
		assertThat(blocked.path("msg").asText()).contains("请先修改初始口令");
		assertThat(jdbc.queryForObject(
			"SELECT must_change_password FROM sys_user WHERE id = ?", Integer.class, userId)).isEqualTo(1);
	}

	@Test
	void adminTypedPasswordMustPassPolicyAndForcesChange() {
		String admin = loginAdmin();
		String username = "it_typed_pwd_" + System.nanoTime() % 1_000_000;
		Map<String, Object> user = new HashMap<>();
		user.put("username", username);
		user.put("status", 1);

		// 太短、够长但只有数字：都建不了，库里不留行（每次昵称不同，避开防重复提交）
		user.put("nickname", "代填口令-太短");
		user.put("password", "1");
		JsonNode tooShort = readBody(post("/system/user/submit", user, admin));
		assertThat(tooShort.path("code").asInt()).as(tooShort.path("msg").asText()).isEqualTo(400);
		assertThat(tooShort.path("msg").asText()).contains("密码至少");
		user.put("nickname", "代填口令-只有数字");
		user.put("password", "1234567890");
		JsonNode tooSimple = readBody(post("/system/user/submit", user, admin));
		assertThat(tooSimple.path("code").asInt()).as(tooSimple.path("msg").asText()).isEqualTo(400);
		assertThat(tooSimple.path("msg").asText()).contains("至少 3 类");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM sys_user WHERE username = ?", Integer.class, username))
			.isZero();

		// 过了强度规则的口令能建，不返回一次性口令；请求里带 mustChangePassword=0 也不算数
		user.put("nickname", "代填口令-合规");
		user.put("password", NEW_USER_PASSWORD);
		user.put("mustChangePassword", 0);
		JsonNode created = readBody(post("/system/user/submit", user, admin));
		assertThat(created.path("code").asInt()).as(created.path("msg").asText()).isEqualTo(200);
		assertThat(created.path("data").path("initialPassword").asText()).isEmpty();
		assertThat(jdbc.queryForObject(
			"SELECT must_change_password FROM sys_user WHERE username = ? AND tenant_id = ?",
			Integer.class, username, PLATFORM_TENANT)).isEqualTo(1);

		// 代填的口令能登录，但改密前业务接口被拒
		String token = login(PLATFORM_TENANT, username, NEW_USER_PASSWORD);
		assertThat(readBody(get("/auth/info", token)).path("data").path("needChangePassword").asBoolean()).isTrue();
		JsonNode blocked = readBody(post("/auth/update-avatar", Map.of("avatar", "x"), token));
		assertThat(blocked.path("code").asInt()).as(blocked.path("msg").asText()).isEqualTo(400);
		assertThat(blocked.path("msg").asText()).contains("请先修改初始口令");

		// 本人改密后放行
		String publicKey = readBody(get("/auth/sm2-public-key", null)).path("data").path("publicKey").asText();
		String newPwd = "ItTyped@Done67x";
		Map<String, Object> body = new HashMap<>();
		body.put("oldPassword", sm2Encrypt(NEW_USER_PASSWORD, publicKey));
		body.put("newPassword", sm2Encrypt(newPwd, publicKey));
		JsonNode changed = readBody(post("/auth/update-password", body, token));
		assertThat(changed.path("code").asInt()).as(changed.path("msg").asText()).isEqualTo(200);
		String fresh = login(PLATFORM_TENANT, username, newPwd);
		assertThat(readBody(get("/auth/info", fresh)).path("data").path("needChangePassword").asBoolean()).isFalse();
	}

	@Test
	void editRejectsPasswordAndKeepsTheOldOne() {
		String admin = loginAdmin();
		String username = "it_edit_pwd_" + System.nanoTime() % 1_000_000;
		Map<String, Object> user = new HashMap<>();
		user.put("username", username);
		user.put("nickname", "编辑不改口令");
		user.put("password", NEW_USER_PASSWORD);
		user.put("status", 1);
		JsonNode created = readBody(post("/system/user/submit", user, admin));
		assertThat(created.path("code").asInt()).as(created.path("msg").asText()).isEqualTo(200);
		long userId = readBody(get("/system/user/page?pageNum=1&pageSize=50&username=" + username, admin))
			.path("data").path("records").get(0).path("id").asLong();

		// 带了口令：整个保存被拒，同一次请求里的昵称也不生效
		String smuggled = "ItOther@98765";
		Map<String, Object> edit = new HashMap<>();
		edit.put("id", userId);
		edit.put("nickname", "顺带改口令");
		edit.put("password", smuggled);
		JsonNode rejected = readBody(post("/system/user/submit", edit, admin));
		assertThat(rejected.path("code").asInt()).as(rejected.path("msg").asText()).isEqualTo(400);
		assertThat(rejected.path("msg").asText()).contains("重置密码");
		assertThat(jdbc.queryForObject("SELECT nickname FROM sys_user WHERE id = ?", String.class, userId))
			.isEqualTo("编辑不改口令");

		// 口令传空串（管理端的编辑弹窗就是这么传的）照常保存，口令不动
		edit.put("nickname", "空口令照常保存");
		edit.put("password", "");
		JsonNode saved = readBody(post("/system/user/submit", edit, admin));
		assertThat(saved.path("code").asInt()).as(saved.path("msg").asText()).isEqualTo(200);
		assertThat(jdbc.queryForObject("SELECT nickname FROM sys_user WHERE id = ?", String.class, userId))
			.isEqualTo("空口令照常保存");

		// 原口令还能登录，被拒的那个口令登录不了
		assertThat(login(PLATFORM_TENANT, username, NEW_USER_PASSWORD)).isNotBlank();
		String publicKey = readBody(get("/auth/sm2-public-key", null)).path("data").path("publicKey").asText();
		assertThat(readBody(post("/auth/login", Map.of(
			"tenantId", PLATFORM_TENANT,
			"username", username,
			"password", sm2Encrypt(smuggled, publicKey),
			"clientId", CLIENT_WEB), null)).path("code").asInt()).isEqualTo(400);
	}

}
