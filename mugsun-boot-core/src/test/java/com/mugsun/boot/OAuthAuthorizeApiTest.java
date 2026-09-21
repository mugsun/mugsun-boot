package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OAuth 授权端点 302 目标必须来自 {@code mugsun.web.front-url}，禁止写死 localhost:3006。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OAuthAuthorizeApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final String REDIRECT_URI = "http://localhost:3006/callback";

	private String clientId;

	@BeforeAll
	void setupClient() {
		String adminToken = loginAdmin();
		Map<String, Object> client = new HashMap<>();
		client.put("name", "IT同意页跳转客户端-" + TS);
		client.put("grantTypes", "authorization_code");
		client.put("scopes", "read");
		client.put("redirectUri", REDIRECT_URI);
		client.put("accessTokenValidity", 7200);
		JsonNode created = readBody(post("/system/oauth-client/save", client, adminToken));
		assertThat(created.path("code").asInt()).as("建客户端：" + created.path("msg").asText()).isEqualTo(200);
		clientId = created.path("data").path("clientId").asText();
		assertThat(clientId).startsWith("mc_");
	}

	@Test
	void authorizeRedirectsToConfiguredFrontUrl() {
		String url = "http://127.0.0.1:" + port + "/oauth2/authorize?response_type=code&client_id=" + clientId
			+ "&redirect_uri=" + REDIRECT_URI + "&state=it-consent";
		ResponseEntity<Void> resp = getNoFollow(url);
		assertThat(resp.getStatusCode().value()).as("授权端点应 302 到同意页").isEqualTo(302);
		URI location = resp.getHeaders().getLocation();
		assertThat(location).as("Location 头").isNotNull();
		String loc = location.toString();
		assertThat(loc).startsWith("http://frontend.test:3006/#/oauth-consent?client_id=" + clientId);
		assertThat(loc).doesNotContain("localhost:3006/#/oauth-consent");
		assertThat(loc).contains("state=it-consent");
	}

	private ResponseEntity<Void> getNoFollow(String url) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
			@Override
			protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
				super.prepareConnection(connection, httpMethod);
				connection.setInstanceFollowRedirects(false);
			}
		};
		RestTemplate rt = new RestTemplate(factory);
		return rt.exchange(url, HttpMethod.GET, HttpEntity.EMPTY, Void.class);
	}
}
