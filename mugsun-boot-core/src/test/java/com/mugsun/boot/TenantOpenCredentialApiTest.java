package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.oauth.OpenApiSignService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

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
 * boot#101：删/停租户后 OAuth 令牌与开放凭据不可再用；发令牌亦拒；删后级联清客户端并撤 Redis 令牌。
 */
class TenantOpenCredentialApiTest extends AbstractIntegrationTest {

	@Autowired
	private OpenApiSignService signService;
	@Autowired
	private StringRedisTemplate redis;

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

	private long tenantRowId(String code) throws Exception {
		try (Connection c = db()) {
			return scalar(c, "SELECT id FROM sys_tenant WHERE tenant_code = ? AND is_deleted = 0", code);
		}
	}

	private Credential seedOauth(String tenantCode) throws Exception {
		long seq = System.nanoTime() % 1_000_000_000L;
		String clientId = "mc_it101_" + seq;
		String secret = "sec_" + UUID.randomUUID().toString().replace("-", "");
		try (Connection c = db()) {
			exec(c, "INSERT INTO sys_oauth_client (id, tenant_id, name, client_id, client_secret, grant_types, scopes, "
					+ "access_token_validity, status, is_deleted) VALUES (?, ?, 'IT#101', ?, ?, 'client_credentials', 'read', 7200, 1, 0)",
				9_101_000_000_000_000_000L + seq * 10, tenantCode, clientId, secret);
			exec(c, "INSERT INTO sys_api_key (id, tenant_id, name, access_key, secret_key, scope, status, is_deleted) "
					+ "VALUES (?, ?, 'IT#101-ak', ?, ?, 'read', 1, 0)",
				9_101_000_000_000_000_000L + seq * 10 + 1, tenantCode, "ak_" + seq, "sk_" + seq);
		}
		return new Credential(clientId, secret);
	}

	private ResponseEntity<String> postForm(String url, Map<String, String> form) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
		form.forEach(body::add);
		return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
	}

	private String issueAccess(Credential cred) {
		ResponseEntity<String> resp = postForm("/oauth2/token", Map.of(
			"grant_type", "client_credentials",
			"client_id", cred.clientId,
			"client_secret", cred.secret,
			"scope", "read"));
		assertThat(resp.getStatusCode().value()).as("换 token：" + resp.getBody()).isEqualTo(200);
		String token = readBody(resp).path("access_token").asText();
		assertThat(token).startsWith("mo_");
		return token;
	}

	private ResponseEntity<String> openPing(String accessToken) {
		Object prev = ReflectionTestUtils.getField(signService, "signRequired");
		ReflectionTestUtils.setField(signService, "signRequired", false);
		try {
			HttpHeaders headers = new HttpHeaders();
			headers.set(TOKEN_HEADER, "Bearer " + accessToken);
			return rest.exchange("/open/ping", HttpMethod.GET, new HttpEntity<>(headers), String.class);
		} finally {
			ReflectionTestUtils.setField(signService, "signRequired", prev);
		}
	}

	private void setTenantStatus(String adminToken, long id, String code, int status) {
		Map<String, Object> body = new HashMap<>();
		body.put("id", id);
		body.put("tenantName", "IT#101-" + code);
		body.put("status", status);
		body.put("accountCount", -1);
		JsonNode resp = readBody(post("/system/tenant/update", body, adminToken));
		assertThat(resp.path("code").asInt()).as("改租户状态：" + resp.path("msg").asText()).isEqualTo(200);
	}

	@Test
	void disabledTenantBlocksTokenIssueAndOpenApi() throws Exception {
		String admin = loginAdmin();
		String code = createTenant(admin, "停用后凭据IT");
		long id = tenantRowId(code);
		Credential cred = seedOauth(code);
		String access = issueAccess(cred);
		assertThat(openPing(access).getStatusCode().value()).isEqualTo(200);

		setTenantStatus(admin, id, code, 0);

		ResponseEntity<String> issue = postForm("/oauth2/token", Map.of(
			"grant_type", "client_credentials",
			"client_id", cred.clientId,
			"client_secret", cred.secret));
		assertThat(issue.getStatusCode().value()).as("停用后不得换发：" + issue.getBody()).isEqualTo(401);
		assertThat(readBody(issue).path("error").asText()).isEqualTo("invalid_client");

		ResponseEntity<String> ping = openPing(access);
		assertThat(ping.getStatusCode().value()).as("停用后旧令牌：" + ping.getBody()).isEqualTo(403);
		assertThat(readBody(ping).path("msg").asText()).contains("停用");
	}

	@Test
	void deletedTenantRevokesTokensAndCascadesCredentials() throws Exception {
		String admin = loginAdmin();
		String code = createTenant(admin, "删除后凭据IT");
		long id = tenantRowId(code);
		Credential cred = seedOauth(code);
		String access = issueAccess(cred);
		assertThat(redis.hasKey("mugsun:oauth:token:" + access)).isTrue();
		assertThat(openPing(access).getStatusCode().value()).isEqualTo(200);

		JsonNode removed = readBody(post("/system/tenant/remove", List.of(id), admin));
		assertThat(removed.path("code").asInt()).as("删租户：" + removed.path("msg").asText()).isEqualTo(200);

		ResponseEntity<String> issue = postForm("/oauth2/token", Map.of(
			"grant_type", "client_credentials",
			"client_id", cred.clientId,
			"client_secret", cred.secret));
		assertThat(issue.getStatusCode().value()).as("删除后不得换发：" + issue.getBody()).isIn(401, 400);

		ResponseEntity<String> ping = openPing(access);
		assertThat(ping.getStatusCode().value()).as("删除后旧令牌：" + ping.getBody()).isIn(401, 403);

		assertThat(redis.hasKey("mugsun:oauth:token:" + access)).as("Redis 令牌应已撤销").isFalse();

		try (Connection c = db()) {
			assertThat(scalar(c, "SELECT count(*) FROM sys_oauth_client WHERE tenant_id = ? AND is_deleted = 0", code)).isZero();
			assertThat(scalar(c, "SELECT count(*) FROM sys_oauth_client WHERE tenant_id = ? AND is_deleted = 1", code)).isPositive();
			assertThat(scalar(c, "SELECT count(*) FROM sys_api_key WHERE tenant_id = ? AND is_deleted = 0", code)).isZero();
			assertThat(scalar(c, "SELECT count(*) FROM sys_api_key WHERE tenant_id = ? AND is_deleted = 1", code)).isPositive();
		}
	}

	private record Credential(String clientId, String secret) {
	}
}
