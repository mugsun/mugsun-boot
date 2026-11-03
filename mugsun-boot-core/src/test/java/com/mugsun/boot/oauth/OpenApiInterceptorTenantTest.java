package com.mugsun.boot.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.tenant.TenantValidator;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** boot#101：/open/** 对停用/删除租户的存量令牌拒绝。 */
@ExtendWith(MockitoExtension.class)
class OpenApiInterceptorTenantTest {

	@Mock
	private OAuthService oauthService;
	@Mock
	private OAuthLogService logService;
	@Mock
	private OpenApiSignService signService;
	@Mock
	private TenantValidator tenantValidator;

	private OpenApiInterceptor interceptor;
	private HandlerMethod handler;

	@BeforeEach
	void setUp() throws Exception {
		interceptor = new OpenApiInterceptor(oauthService, logService, new ObjectMapper(), signService, tenantValidator);
		handler = new HandlerMethod(new OpenApiController(null), OpenApiController.class.getMethod("ping"));
		when(signService.verify(org.mockito.ArgumentMatchers.any(), anyString())).thenReturn(null);
	}

	@Test
	void disabledTenantForbidden() throws Exception {
		when(oauthService.resolveToken("tok")).thenReturn(
			new OAuthService.TokenInfo("mc_x", Set.of(), "700001"));
		when(tenantValidator.validate("700001")).thenReturn("租户已停用");

		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/open/ping");
		req.addHeader("Authorization", "Bearer tok");
		MockHttpServletResponse resp = new MockHttpServletResponse();

		assertThat(interceptor.preHandle(req, resp, handler)).isFalse();
		assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
		assertThat(resp.getContentAsString()).contains("租户已停用");
		verify(logService).record(org.mockito.ArgumentMatchers.eq("mc_x"),
			org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull(),
			org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.eq("租户已停用"));
	}

	@Test
	void missingTenantUnauthorized() throws Exception {
		when(oauthService.resolveToken("tok")).thenReturn(
			new OAuthService.TokenInfo("mc_x", Set.of(), "700099"));
		when(tenantValidator.validate("700099")).thenReturn("租户不存在");

		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/open/ping");
		req.addHeader("Authorization", "Bearer tok");
		MockHttpServletResponse resp = new MockHttpServletResponse();

		assertThat(interceptor.preHandle(req, resp, handler)).isFalse();
		assertThat(resp.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
		assertThat(resp.getContentAsString()).contains("租户不存在");
	}
}
