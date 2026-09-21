package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OAuth2 刷新轮换（G66）回归：授权码 + PKCE（S256）换 token → refresh 轮换（旧 refresh 用后即焚）→
 * introspect 令牌状态 → revoke 后 refresh 被拒、旧 access 失活。
 * 前置：经 admin API 建 OAuth 客户端（authorization_code + refresh_token），走真实端点
 * （/oauth2/authorize/confirm → /oauth2/token → /oauth2/introspect → /oauth2/revoke，form 入参 + 标准 JSON 响应）。
 * <p>用例严格有序（令牌状态前后依赖）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OAuthRefreshApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final String REDIRECT_URI = "http://localhost:3006/callback";
	/** PKCE verifier（RFC7636 要求 43~128 字符） */
	private static final String CODE_VERIFIER = "it-verifier-" + TS + "0123456789abcdef0123456789";

	private String clientId;
	private String clientSecret;
	private String code;
	private String access1;
	private String refresh1;
	private String access2;
	private String refresh2;

	@BeforeAll
	void setupClientAndCode() throws Exception {
		String adminToken = loginAdmin();

		// 1) 建客户端（明文密钥仅此一次返回）
		Map<String, Object> client = new HashMap<>();
		client.put("name", "IT刷新轮换客户端-" + TS);
		client.put("grantTypes", "authorization_code,refresh_token");
		client.put("scopes", "read");
		client.put("redirectUri", REDIRECT_URI);
		client.put("accessTokenValidity", 7200);
		client.put("remark", "集成测试");
		JsonNode created = readBody(post("/system/oauth-client/save", client, adminToken));
		assertThat(created.path("code").asInt()).as("建客户端：" + created.path("msg").asText()).isEqualTo(200);
		clientId = created.path("data").path("clientId").asText();
		clientSecret = created.path("data").path("clientSecret").asText();
		assertThat(clientId).startsWith("mc_");
		assertThat(clientSecret).isNotBlank();

		// 2) 登录用户同意授权 → 一次性授权码（S256 挑战随码登记）
		String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
			MessageDigest.getInstance("SHA-256").digest(CODE_VERIFIER.getBytes(StandardCharsets.US_ASCII)));
		Map<String, Object> confirm = new HashMap<>();
		confirm.put("clientId", clientId);
		confirm.put("scope", "read");
		confirm.put("redirectUri", REDIRECT_URI);
		confirm.put("codeChallenge", codeChallenge);
		confirm.put("codeChallengeMethod", "S256");
		confirm.put("state", "it-state-" + TS);
		JsonNode confirmed = readBody(post("/oauth2/authorize/confirm", confirm, adminToken));
		assertThat(confirmed.path("code").asInt()).as("授权确认：" + confirmed.path("msg").asText()).isEqualTo(200);
		code = confirmed.path("data").path("code").asText();
		assertThat(code).isNotBlank();
		assertThat(confirmed.path("data").path("state").asText()).isEqualTo("it-state-" + TS);
	}

	@Test
	@Order(1)
	void authorizationCodeWithPkceIssuesTokens() {
		ResponseEntity<String> response = postForm("/oauth2/token", Map.of(
			"grant_type", "authorization_code",
			"code", code,
			"redirect_uri", REDIRECT_URI,
			"code_verifier", CODE_VERIFIER,
			"client_id", clientId,
			"client_secret", clientSecret));
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		JsonNode r = readBody(response);
		access1 = r.path("access_token").asText();
		refresh1 = r.path("refresh_token").asText();
		assertThat(access1).startsWith("mo_");
		assertThat(refresh1).as("授权码模式应发 refresh_token").startsWith("mr_");
		assertThat(r.path("token_type").asText()).isEqualTo("Bearer");
		assertThat(r.path("expires_in").asInt()).isEqualTo(7200);
		assertThat(r.path("scope").asText()).isEqualTo("read");
	}

	@Test
	@Order(2)
	void refreshRotatesAndKillsOldRefresh() {
		// 轮换：旧 refresh 换新 access + 新 refresh，两对令牌均不同
		JsonNode rotated = readBody(postForm("/oauth2/token", Map.of(
			"grant_type", "refresh_token",
			"refresh_token", refresh1,
			"client_id", clientId,
			"client_secret", clientSecret)));
		access2 = rotated.path("access_token").asText();
		refresh2 = rotated.path("refresh_token").asText();
		assertThat(access2).startsWith("mo_").isNotEqualTo(access1);
		assertThat(refresh2).startsWith("mr_").isNotEqualTo(refresh1);

		// 用后即焚：旧 refresh 再换一律 invalid_grant
		ResponseEntity<String> reused = postForm("/oauth2/token", Map.of(
			"grant_type", "refresh_token",
			"refresh_token", refresh1,
			"client_id", clientId,
			"client_secret", clientSecret));
		assertThat(reused.getStatusCode().value()).isEqualTo(400);
		JsonNode err = readBody(reused);
		assertThat(err.path("error").asText()).isEqualTo("invalid_grant");
	}

	@Test
	@Order(3)
	void introspectShowsNewAccessActive() {
		JsonNode active = readBody(postForm("/oauth2/introspect", Map.of(
			"token", access2,
			"client_id", clientId,
			"client_secret", clientSecret)));
		assertThat(active.path("active").asBoolean()).as("新 access 应激活").isTrue();
		assertThat(active.path("token_type").asText()).isEqualTo("access_token");
		assertThat(active.path("client_id").asText()).isEqualTo(clientId);
		assertThat(active.path("scope").asText()).isEqualTo("read");
		assertThat(active.path("sub").asText()).as("授权码模式应带用户 sub").isNotBlank();

		// 未知令牌 fail-closed：active=false（而非报错泄露）
		JsonNode unknown = readBody(postForm("/oauth2/introspect", Map.of(
			"token", "mo_definitely_not_exist",
			"client_id", clientId,
			"client_secret", clientSecret)));
		assertThat(unknown.path("active").asBoolean()).isFalse();
	}

	@Test
	@Order(4)
	void revokeBlocksRefreshAndDeactivatesOldAccess() {
		// revoke 新 refresh 后：再换 token 被拒（invalid_grant）
		ResponseEntity<String> revokeRefresh = postForm("/oauth2/revoke", Map.of(
			"token", refresh2,
			"client_id", clientId,
			"client_secret", clientSecret));
		assertThat(revokeRefresh.getStatusCode().value()).isEqualTo(200);
		ResponseEntity<String> refreshAfterRevoke = postForm("/oauth2/token", Map.of(
			"grant_type", "refresh_token",
			"refresh_token", refresh2,
			"client_id", clientId,
			"client_secret", clientSecret));
		assertThat(refreshAfterRevoke.getStatusCode().value()).isEqualTo(400);
		assertThat(readBody(refreshAfterRevoke).path("error").asText()).isEqualTo("invalid_grant");

		// revoke 旧 access 后：introspect 失活（旧 access 在刷新轮换后仍存活至自然过期，显式撤销方失活）
		ResponseEntity<String> revokeAccess = postForm("/oauth2/revoke", Map.of(
			"token", access1,
			"client_id", clientId,
			"client_secret", clientSecret));
		assertThat(revokeAccess.getStatusCode().value()).isEqualTo(200);
		JsonNode introspected = readBody(postForm("/oauth2/introspect", Map.of(
			"token", access1,
			"client_id", clientId,
			"client_secret", clientSecret)));
		assertThat(introspected.path("active").asBoolean()).as("revoke 后旧 access 应失活").isFalse();
	}

	/** OAuth2 公共端点 form 提交（无 Sa-Token 头，客户端凭证走 form 参数；响应为标准 JSON 而非 R 信封） */
	private ResponseEntity<String> postForm(String url, Map<String, String> form) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
		MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
		form.forEach(body::add);
		return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
	}
}
