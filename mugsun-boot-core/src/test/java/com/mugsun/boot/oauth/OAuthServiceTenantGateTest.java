package com.mugsun.boot.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.oauth.entity.SysOauthClient;
import com.mugsun.boot.oauth.mapper.SysOauthClientMapper;
import com.mugsun.boot.tenant.TenantValidator;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** boot#101：发令牌校验租户；按租户 SCAN 撤销 Redis 令牌。 */
@ExtendWith(MockitoExtension.class)
class OAuthServiceTenantGateTest {

	@Mock
	private SysOauthClientMapper clientMapper;
	@Mock
	private StringRedisTemplate redisTemplate;
	@Mock
	private ValueOperations<String, String> values;
	@Mock
	private TenantValidator tenantValidator;

	private OAuthService service;

	@BeforeEach
	void setUp() {
		service = new OAuthService(clientMapper, redisTemplate, new ObjectMapper(), tenantValidator);
	}

	private SysOauthClient enabledClient(String tenantId) {
		SysOauthClient c = new SysOauthClient();
		c.setClientId("mc_it");
		c.setClientSecret("sec");
		c.setStatus(1);
		c.setTenantId(tenantId);
		c.setGrantTypes("client_credentials");
		c.setScopes("read");
		c.setAccessTokenValidity(7200);
		return c;
	}

	@Test
	void loadEnabledClientRejectsDisabledTenant() {
		when(clientMapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(enabledClient("700001"));
		when(tenantValidator.validate("700001")).thenReturn("租户已停用");

		assertThatThrownBy(() -> service.loadEnabledClient("mc_it"))
			.isInstanceOf(OAuth2Exception.class)
			.satisfies(ex -> {
				OAuth2Exception o = (OAuth2Exception) ex;
				assertThat(o.getError()).isEqualTo("invalid_client");
				assertThat(o.getStatus()).isEqualTo(401);
				assertThat(o.getMessage()).isEqualTo("租户已停用");
			});
	}

	@Test
	void loadEnabledClientRejectsMissingTenant() {
		when(clientMapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(enabledClient("700099"));
		when(tenantValidator.validate("700099")).thenReturn("租户不存在");

		assertThatThrownBy(() -> service.authenticateClient("mc_it", "sec"))
			.isInstanceOf(OAuth2Exception.class)
			.hasMessage("租户不存在");
	}

	@Test
	void clientCredentialsIssuesWhenTenantOk() {
		when(clientMapper.selectOneByQuery(any(QueryWrapper.class))).thenReturn(enabledClient("700001"));
		when(tenantValidator.validate("700001")).thenReturn(null);
		when(redisTemplate.opsForValue()).thenReturn(values);

		OAuthService.TokenResult token = service.clientCredentials("mc_it", "sec", "read");
		assertThat(token.accessToken()).startsWith("mo_");
		verify(values).set(ArgumentMatchers.startsWith("mugsun:oauth:token:"), anyString(), eq(Duration.ofSeconds(7200)));
	}
}
