package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * X-003 社交绑定不能被跨站诱导：state 按用途发放、用一次即作废（走真实 Redis，本地 mock 来源）。
 * 登录用的 state 不能拿去绑定，绑定用的 state 不能拿去登录，同一个 state 第二次用被拒，
 * 绑定入口要求登录；正常的「个人中心绑定 → 社交登录」照常可用。
 * 别人发起的绑定 state、同一来源绑第二个账号见 SocialServiceTest（mock 来源的第三方身份是固定的）。
 */
@TestPropertySource(properties = {
	"mugsun.social.mock-enabled=true",
	"mugsun.social.type.mock.client-id=it-mock",
	"mugsun.social.type.mock.client-secret=it-mock-secret",
	"mugsun.social.type.mock.redirect-uri=http://frontend.test:3006/#/auth/social-callback?source=mock"
})
class SocialStateApiTest extends AbstractIntegrationTest {

	@AfterEach
	void unbindAdmin() {
		rest.exchange("/auth/social/unbind/mock", HttpMethod.DELETE, new HttpEntity<>(authHeaders(loginAdmin())), String.class);
	}

	@Test
	void bindRenderRequiresLogin() {
		assertThat(get("/auth/social/bind-render/mock", null).getStatusCode().value()).isEqualTo(401);
	}

	@Test
	void loginStateCannotBindAndIsBurnt() {
		String admin = loginAdmin();
		String state = stateOf(get("/auth/social/render/mock", null));

		JsonNode bind = readBody(post("/auth/social/bind", callback(state), admin));
		assertThat(bind.path("code").asInt()).isNotEqualTo(200);
		assertThat(bind.path("msg").asText()).contains("不是你在个人中心发起的绑定");

		JsonNode reuse = readBody(post("/auth/social/login", callback(state), null));
		assertThat(reuse.path("code").asInt()).isNotEqualTo(200);
		assertThat(reuse.path("msg").asText()).contains("已过期或已经用过");
	}

	@Test
	void bindStateCannotLogin() {
		String admin = loginAdmin();
		String state = stateOf(get("/auth/social/bind-render/mock", admin));

		JsonNode login = readBody(post("/auth/social/login", callback(state), null));
		assertThat(login.path("code").asInt()).isNotEqualTo(200);
		assertThat(login.path("msg").asText()).contains("不是从登录页发起的");
	}

	@Test
	void ownBindStateWorksOnceThenSocialLoginWorks() {
		String admin = loginAdmin();
		String state = stateOf(get("/auth/social/bind-render/mock", admin));

		JsonNode bind = readBody(post("/auth/social/bind", callback(state), admin));
		assertThat(bind.path("code").asInt()).as(bind.path("msg").asText()).isEqualTo(200);

		JsonNode replay = readBody(post("/auth/social/bind", callback(state), admin));
		assertThat(replay.path("code").asInt()).isNotEqualTo(200);
		assertThat(replay.path("msg").asText()).contains("已过期或已经用过");

		String loginState = stateOf(get("/auth/social/render/mock", null));
		JsonNode login = readBody(post("/auth/social/login", callback(loginState), null));
		assertThat(login.path("code").asInt()).as(login.path("msg").asText()).isEqualTo(200);
		assertThat(login.path("data").path("token").asText()).isNotBlank();
	}

	private static Map<String, String> callback(String state) {
		return Map.of("source", "mock", "code", "mockcode-it", "state", state);
	}

	private String stateOf(ResponseEntity<String> render) {
		JsonNode body = readBody(render);
		assertThat(body.path("code").asInt()).as(body.path("msg").asText()).isEqualTo(200);
		String url = body.path("data").path("authorizeUrl").asText();
		String state = UriComponentsBuilder.fromUriString(url).build().getQueryParams().getFirst("state");
		assertThat(state).isNotBlank();
		return state;
	}
}
