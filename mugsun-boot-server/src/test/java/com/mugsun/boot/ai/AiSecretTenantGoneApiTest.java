package com.mugsun.boot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import com.mugsun.boot.common.crypto.Sm4Util;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * boot#101：删/停租户后 AI 密钥（Bearer sk-）调用 /v1 返回 401/403；删后 ai_secret 逻辑删除。
 */
class AiSecretTenantGoneApiTest extends AbstractIntegrationTest {

	private static Connection db() throws SQLException {
		return DriverManager.getConnection(primaryJdbcUrl(), primaryJdbcUsername(), primaryJdbcPassword());
	}

	private static long scalar(Connection c, String sql, Object... args) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			for (int i = 0; i < args.length; i++) {
				ps.setObject(i + 1, args[i]);
			}
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		}
	}

	private static void exec(Connection c, String sql, Object... args) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			for (int i = 0; i < args.length; i++) {
				ps.setObject(i + 1, args[i]);
			}
			ps.executeUpdate();
		}
	}

	private String createTenant(String adminToken, String name) {
		Map<String, Object> tenant = new HashMap<>();
		tenant.put("tenantName", name);
		JsonNode resp = readBody(post("/system/tenant/create", tenant, adminToken));
		assertThat(resp.path("code").asInt()).as("建租户：" + resp.path("msg").asText()).isEqualTo(200);
		return resp.path("data").asText();
	}

	private String seedAiSecret(String tenantCode) throws Exception {
		long seq = System.nanoTime() % 1_000_000_000L;
		String plain = "sk-" + UUID.randomUUID().toString().replace("-", "");
		String prefix = plain.substring(0, 8);
		String cipher = Sm4Util.encrypt(plain);
		try (Connection c = db()) {
			exec(c, "INSERT INTO ai_secret (id, tenant_id, secret_key, secret_prefix, scope, description, status, used_count, is_deleted) "
					+ "VALUES (?, ?, ?, ?, 'tenant', 'IT#101', 1, 0, 0)",
				9_102_000_000_000_000_000L + seq, tenantCode, cipher, prefix);
		}
		return plain;
	}

	private ResponseEntity<String> v1Models(String plainSecret) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(TOKEN_HEADER, "Bearer " + plainSecret);
		return rest.exchange("/v1/models", HttpMethod.GET, new HttpEntity<>(headers), String.class);
	}

	private void setTenantStatus(String adminToken, long id, String code, int status) {
		Map<String, Object> body = new HashMap<>();
		body.put("id", id);
		body.put("tenantName", "IT#101-ai-" + code);
		body.put("status", status);
		body.put("accountCount", -1);
		JsonNode resp = readBody(post("/system/tenant/update", body, adminToken));
		assertThat(resp.path("code").asInt()).as("改状态：" + resp.path("msg").asText()).isEqualTo(200);
	}

	@Test
	void disabledTenantRejectsAiSecret() throws Exception {
		String admin = loginAdmin();
		String code = createTenant(admin, "停用AI密钥IT");
		long id;
		try (Connection c = db()) {
			id = scalar(c, "SELECT id FROM sys_tenant WHERE tenant_code = ? AND is_deleted = 0", code);
		}
		String secret = seedAiSecret(code);
		assertThat(v1Models(secret).getStatusCode().value()).as("启用时应可鉴权").isEqualTo(200);

		setTenantStatus(admin, id, code, 0);
		ResponseEntity<String> denied = v1Models(secret);
		assertThat(denied.getStatusCode().value()).as("停用后：" + denied.getBody()).isEqualTo(403);
		assertThat(readBody(denied).path("msg").asText()).contains("停用");
	}

	@Test
	void deletedTenantRejectsAiSecretAndCascades() throws Exception {
		String admin = loginAdmin();
		String code = createTenant(admin, "删除AI密钥IT");
		long id;
		try (Connection c = db()) {
			id = scalar(c, "SELECT id FROM sys_tenant WHERE tenant_code = ? AND is_deleted = 0", code);
		}
		String secret = seedAiSecret(code);
		assertThat(v1Models(secret).getStatusCode().value()).isEqualTo(200);

		JsonNode removed = readBody(post("/system/tenant/remove", List.of(id), admin));
		assertThat(removed.path("code").asInt()).as("删租户：" + removed.path("msg").asText()).isEqualTo(200);

		ResponseEntity<String> denied = v1Models(secret);
		// 密钥已逻辑删 → authenticate 抛 ServiceException，全局处理为 HTTP 200 + 业务失败码
		assertThat(denied.getStatusCode().value()).as("删除后 HTTP：" + denied.getBody()).isEqualTo(200);
		assertThat(readBody(denied).path("code").asInt()).as("删除后业务码").isNotEqualTo(200);

		try (Connection c = db()) {
			assertThat(scalar(c, "SELECT count(*) FROM ai_secret WHERE tenant_id = ? AND is_deleted = 0", code)).isZero();
			assertThat(scalar(c, "SELECT count(*) FROM ai_secret WHERE tenant_id = ? AND is_deleted = 1", code)).isPositive();
		}
	}
}
