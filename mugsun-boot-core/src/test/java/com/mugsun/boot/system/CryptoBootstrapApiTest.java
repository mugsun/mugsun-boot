package com.mugsun.boot.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import com.mugsun.core.web.crypto.ApiCryptoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** GET /crypto/bootstrap：只对已登录会话下发传输密钥、no-store、不含签名密钥 */
class CryptoBootstrapApiTest extends AbstractIntegrationTest {

	@Autowired
	ApiCryptoService crypto;

	@Autowired
	org.springframework.data.redis.core.StringRedisTemplate redis;

	private ResponseEntity<String> call(String token) {
		HttpHeaders h = new HttpHeaders();
		if (token != null) {
			h.set(TOKEN_HEADER, token);
		}
		return rest.exchange("/crypto/bootstrap", HttpMethod.GET, new HttpEntity<>(h), String.class);
	}

	@Test
	@DisplayName("匿名访问：401，不下发密钥")
	void anonymousRejected() {
		ResponseEntity<String> r = call(null);
		assertThat(r.getStatusCode().value()).isEqualTo(401);
		assertThat(r.getBody() == null ? "" : r.getBody()).doesNotContain(crypto.transportKeyHex());
		assertThat(r.getBody() == null ? "" : r.getBody()).doesNotContain("keyHex");
	}

	@Test
	@DisplayName("已登录：返回与后端同一把传输密钥，Cache-Control: no-store")
	void loggedInGetsKey() throws Exception {
		ResponseEntity<String> r = call(loginAdmin());
		assertThat(r.getStatusCode().value()).isEqualTo(200);
		assertThat(r.getHeaders().getCacheControl()).contains("no-store");
		JsonNode data = om.readTree(r.getBody()).path("data");
		assertThat(data.path("keyHex").asText()).isEqualTo(crypto.transportKeyHex());
		assertThat(data.path("algorithm").asText()).isEqualTo("SM4-GCM");
	}

	@Test
	@DisplayName("响应里不含签名密钥：字段只有 algorithm/keyHex/keySource，且不出现审计签名私钥、公钥")
	void responseHasNoSigningKey() throws Exception {
		ResponseEntity<String> r = call(loginAdmin());
		JsonNode data = om.readTree(r.getBody()).path("data");
		assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder("algorithm", "keyHex", "keySource");
		String body = r.getBody().toLowerCase(Locale.ROOT);
		String[] sign = com.mugsun.boot.common.crypto.Sm2TestAccess.signKeys();
		assertThat(sign[0]).as("集成测试里签名密钥已自动生成").isNotBlank();
		for (String k : sign) {
			if (k != null && !k.isBlank()) {
				assertThat(body).doesNotContain(k.toLowerCase(Locale.ROOT));
			}
		}
		assertThat(body).doesNotContain("sign").doesNotContain("private");
	}

	@Test
	@DisplayName("超过每用户上限：429，带 no-store，不含密钥")
	void overLimitReturns429() throws Exception {
		String token = loginAdmin();
		// 直接把该用户计数打满，避免发 30 次请求
		String userId = currentLoginId(token);
		redis.opsForValue().set(com.mugsun.boot.common.crypto.CryptoBootstrapRateLimiter.KEY_PREFIX + userId, "1000",
			java.time.Duration.ofSeconds(60));
		try {
			ResponseEntity<String> r = call(token);
			assertThat(r.getStatusCode().value()).isEqualTo(429);
			assertThat(r.getHeaders().getCacheControl()).contains("no-store");
			assertThat(r.getBody()).doesNotContain(crypto.transportKeyHex()).doesNotContain("keyHex");
		} finally {
			redis.delete(com.mugsun.boot.common.crypto.CryptoBootstrapRateLimiter.KEY_PREFIX + userId);
		}
	}

	@Test
	@DisplayName("会话没有租户：403，不含密钥")
	void noTenantReturns403() {
		String token = loginAdmin();
		var session = cn.dev33.satoken.stp.StpUtil.getSessionByLoginId(currentLoginId(token));
		Object saved = session.get(com.mugsun.boot.tenant.TenantContext.TENANT_SESSION_KEY);
		session.delete(com.mugsun.boot.tenant.TenantContext.TENANT_SESSION_KEY);
		try {
			ResponseEntity<String> r = call(token);
			assertThat(r.getStatusCode().value()).isEqualTo(403);
			assertThat(r.getBody() == null ? "" : r.getBody()).doesNotContain(crypto.transportKeyHex());
		} finally {
			session.set(com.mugsun.boot.tenant.TenantContext.TENANT_SESSION_KEY, saved);
		}
	}

	private static String currentLoginId(String token) {
		Object id = cn.dev33.satoken.stp.StpUtil.getLoginIdByToken(token);
		return String.valueOf(id);
	}
}
