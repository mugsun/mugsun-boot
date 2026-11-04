package com.mugsun.boot.monitor;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

/** #94：ActuatorAuthFilter 按 UrlPathHelper 归一后的路径鉴权，与 Spring 映射一致 */
class ActuatorAuthFilterTest {

	private final ActuatorAuthFilter filter = new ActuatorAuthFilter(new ObjectMapper());

	/**
	 * 构造请求：requestURI 可为变体原文；servletPath 模拟容器归一后的映射路径（可空，表示仅靠 URI 解码）。
	 */
	private static MockHttpServletRequest request(String uri, String servletPath) {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
		if (servletPath != null) {
			req.setServletPath(servletPath);
		}
		return req;
	}

	private MockHttpServletResponse run(MockHttpServletRequest req, MockFilterChain chain) throws Exception {
		MockHttpServletResponse resp = new MockHttpServletResponse();
		filter.doFilter(req, resp, chain);
		return resp;
	}

	@Test
	@DisplayName("普通受保护路径未登录 → 401，不进入后续链")
	void protectedPathRequiresLogin() throws Exception {
		MockFilterChain chain = new MockFilterChain();
		try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
			stp.when(StpUtil::checkLogin)
				.thenThrow(NotLoginException.newInstance("login", NotLoginException.NOT_TOKEN,
					NotLoginException.NOT_TOKEN_MESSAGE, null));
			MockHttpServletResponse resp = run(request("/actuator/metrics", "/actuator/metrics"), chain);
			assertThat(resp.getStatus()).isEqualTo(401);
			assertThat(chain.getRequest()).isNull();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"/actuator/health", "/actuator/info", "/actuator/health/liveness",
		"/actuator/health/", "/actuator/info/"})
	@DisplayName("health/info（及子路径）公开，未登录也放行")
	void publicEndpointsPassWithoutLogin(String path) throws Exception {
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletRequest req = request(path, path);
		MockHttpServletResponse resp = run(req, chain);
		assertThat(resp.getStatus()).isEqualTo(200);
		assertThat(chain.getRequest()).isSameAs(req);
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource({
		"尾斜杠, /actuator/metrics/, /actuator/metrics/",
		"分号参数, /actuator/metrics;jsessionid=1, /actuator/metrics",
		"中段矩阵, /actuator;x=1/metrics, /actuator/metrics",
		"编码字母, /actuator/metr%69cs, /actuator/metrics",
		"双斜杠, //actuator/metrics, /actuator/metrics",
		"中段双斜杠, /actuator//metrics, /actuator/metrics",
		"点段, /./actuator/metrics, /actuator/metrics",
		"上级段, /foo/../actuator/metrics, /actuator/metrics",
	})
	@DisplayName("能映射到受保护端点的路径变体 → 未登录 401")
	void protectedVariantsRequireLogin(String name, String uri, String servletPath) throws Exception {
		MockFilterChain chain = new MockFilterChain();
		try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
			stp.when(StpUtil::checkLogin)
				.thenThrow(NotLoginException.newInstance("login", NotLoginException.NOT_TOKEN,
					NotLoginException.NOT_TOKEN_MESSAGE, null));
			MockHttpServletResponse resp = run(request(uri, servletPath), chain);
			assertThat(resp.getStatus()).as(name).isEqualTo(401);
			assertThat(chain.getRequest()).as(name).isNull();
		}
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource({
		"健康编码, /actuator/hea%6cth, /actuator/health",
		"健康分号, /actuator/health;jsessionid=1, /actuator/health",
		"信息编码, /actuator/inf%6f, /actuator/info",
	})
	@DisplayName("公开端点的路径变体归一后仍公开")
	void publicVariantsStillOpen(String name, String uri, String servletPath) throws Exception {
		MockFilterChain chain = new MockFilterChain();
		MockHttpServletRequest req = request(uri, servletPath);
		MockHttpServletResponse resp = run(req, chain);
		assertThat(resp.getStatus()).as(name).isEqualTo(200);
		assertThat(chain.getRequest()).as(name).isSameAs(req);
	}

	@Test
	@DisplayName("带 context-path 时按应用内路径判断")
	void contextPathHonoured() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/actuator/metrics");
		req.setContextPath("/api");
		req.setServletPath("/actuator/metrics");
		assertThat(ActuatorAuthFilter.lookupPath(req)).isEqualTo("/actuator/metrics");
		assertThat(filter.isGuarded(req)).isTrue();

		MockHttpServletRequest health = new MockHttpServletRequest("GET", "/api/actuator/health");
		health.setContextPath("/api");
		health.setServletPath("/actuator/health");
		assertThat(filter.isGuarded(health)).isFalse();
	}

	@Test
	@DisplayName("非 actuator 路径与形似前缀不鉴权")
	void nonActuatorSkipped() {
		assertThat(ActuatorAuthFilter.isGuardedPath("/api/users")).isFalse();
		assertThat(ActuatorAuthFilter.isGuardedPath("/actuatorx")).isFalse();
		assertThat(ActuatorAuthFilter.isGuardedPath("/actuators")).isFalse();
		assertThat(ActuatorAuthFilter.isGuardedPath("/")).isFalse();
		assertThat(ActuatorAuthFilter.isGuardedPath(null)).isFalse();
	}

	@Test
	@DisplayName("段感知：/actuator 根需鉴权；health/info 精确与子路径公开")
	void segmentAwareClassification() {
		assertThat(ActuatorAuthFilter.isGuardedPath("/actuator")).isTrue();
		assertThat(ActuatorAuthFilter.isGuardedPath("/actuator/")).isTrue();
		assertThat(ActuatorAuthFilter.isGuardedPath("/actuator/metrics")).isTrue();
		assertThat(ActuatorAuthFilter.isGuardedPath("/actuator/prometheus")).isTrue();
		assertThat(ActuatorAuthFilter.isGuardedPath("/actuator/health")).isFalse();
		assertThat(ActuatorAuthFilter.isGuardedPath("/actuator/health/liveness")).isFalse();
		assertThat(ActuatorAuthFilter.isGuardedPath("/actuator/info")).isFalse();
	}

	@Test
	@DisplayName("仅 URI 编码、未设 servletPath 时也能解码后鉴权")
	void decodeFromRequestUriAlone() {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/metr%69cs");
		assertThat(ActuatorAuthFilter.lookupPath(req)).isEqualTo("/actuator/metrics");
		assertThat(filter.isGuarded(req)).isTrue();

		MockHttpServletRequest health = new MockHttpServletRequest("GET", "/actuator/hea%6cth");
		assertThat(ActuatorAuthFilter.lookupPath(health)).isEqualTo("/actuator/health");
		assertThat(filter.isGuarded(health)).isFalse();
	}
}
