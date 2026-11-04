package com.mugsun.boot.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** #93：公开白名单段感知匹配 */
class LoginPublicPathsTest {

	@ParameterizedTest
	@ValueSource(strings = {
		"/ping", "/ping/",
		"/auth/login", "/auth/login/",
		"/auth/sm2-public-key",
		"/auth/logout",
		"/app/auth/login",
		"/oauth2/token", "/oauth2/authorize",
		"/csp-report",
		"/actuator/health", "/actuator/health/liveness", "/actuator/info",
		"/auth/captcha/config", "/app/captcha/generate",
		"/auth/avatar/raw/42",
		"/auth/social/render/github",
		"/open/ping", "/open/user/list",
		"/v1/models", "/v1/chat/completions",
		"/track/collect", "/track/config",
		"/file/mugsun/public/a.png",
		"/v3/api-docs", "/v3/api-docs/springdoc",
		"/swagger-ui/index.html", "/webjars/swagger-ui/swagger-ui.css"
	})
	@DisplayName("白名单路径公开")
	void publicPaths(String path) {
		assertThat(LoginPublicPaths.isPublic(path)).as(path).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"/auth/info",
		"/auth/update-password",
		"/auth/social/bind",
		"/app/auth/me",
		"/app/auth/logout",
		"/oauth2/authorize/info",
		"/oauth2/authorize/confirm",
		"/actuator",
		"/actuator/metrics",
		"/actuator/prometheus",
		"/actuatorx/health",
		"/openx/ping",
		"/v1x/models",
		"/system/user/list",
		"/system/track/app/list",
		"/auth/avatar/upload"
	})
	@DisplayName("非白名单须登录")
	void protectedPaths(String path) {
		assertThat(LoginPublicPaths.isPublic(path)).as(path).isFalse();
	}

	@Test
	@DisplayName("空/null 不公开")
	void nullOrEmptyNotPublic() {
		assertThat(LoginPublicPaths.isPublic(null)).isFalse();
		assertThat(LoginPublicPaths.isPublic("")).isFalse();
	}
}
