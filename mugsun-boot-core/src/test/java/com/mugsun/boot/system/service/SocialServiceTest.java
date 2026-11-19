package com.mugsun.boot.system.service;

import com.mugsun.boot.social.MugsunAuthStateRedisCache;
import com.mugsun.boot.social.SocialAuthFactory;
import com.mugsun.boot.system.entity.SysUserOauth;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.system.mapper.SysUserOauthMapper;
import com.mugsun.boot.tenant.TenantValidator;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import me.zhyd.oauth.enums.AuthResponseStatus;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthResponse;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * X-003：state 按用途核对、用一次即作废；用途不符时根本不去第三方换 code。
 * 另外：同一来源只能绑一个账号，解绑删掉这个来源的全部绑定。
 */
class SocialServiceTest {

	private final SysUserOauthMapper oauthMapper = mock(SysUserOauthMapper.class);
	private final SocialAuthFactory factory = mock(SocialAuthFactory.class);
	private final MugsunAuthStateRedisCache stateCache = mock(MugsunAuthStateRedisCache.class);
	private final AuthRequest authRequest = mock(AuthRequest.class);
	private SocialService service;

	@BeforeEach
	void setUp() {
		service = new SocialService(oauthMapper, mock(SysUserMapper.class), mock(PasswordEncoder.class),
			factory, mock(TenantValidator.class), stateCache);
		when(factory.getAuthRequest(anyString())).thenReturn(authRequest);
		when(authRequest.login(any(AuthCallback.class))).thenReturn(AuthResponse.<AuthUser>builder()
			.code(AuthResponseStatus.SUCCESS.getCode())
			.data(AuthUser.builder().uuid("open-1").build())
			.build());
	}

	@Test
	void loginStateCannotBindAndNeverReachesThirdParty() {
		when(stateCache.takePurpose("s1")).thenReturn("login:mock");

		assertThatThrownBy(() -> service.bindByCode(1L, "mock", "c", "s1"))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("不是你在个人中心发起的绑定");
		verify(authRequest, never()).login(any(AuthCallback.class));
		verify(stateCache).remove("s1");
		verify(oauthMapper, never()).insert(any(SysUserOauth.class));
	}

	@Test
	void bindStateOfAnotherUserOrSourceRejected() {
		when(stateCache.takePurpose("s1")).thenReturn("bind:mock:1");
		when(stateCache.takePurpose("s2")).thenReturn("bind:github:1");

		assertThatThrownBy(() -> service.bindByCode(2L, "mock", "c", "s1"))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("不是你在个人中心发起的绑定");
		assertThatThrownBy(() -> service.bindByCode(1L, "mock", "c", "s2"))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("不是你在个人中心发起的绑定");
		verify(authRequest, never()).login(any(AuthCallback.class));
		verify(oauthMapper, never()).insert(any(SysUserOauth.class));
	}

	@Test
	void bindStateCannotLogin() {
		when(stateCache.takePurpose("s1")).thenReturn("bind:mock:1");

		assertThatThrownBy(() -> service.loginByCode("mock", "c", "s1", false))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("不是从登录页发起的");
		verify(authRequest, never()).login(any(AuthCallback.class));
	}

	@Test
	void unknownUsedOrBlankStateRejected() {
		assertThatThrownBy(() -> service.loginByCode("mock", "c", "used", false))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("已过期或已经用过");
		assertThatThrownBy(() -> service.bindByCode(1L, "mock", "c", " "))
			.hasMessageContaining("已过期或已经用过");
		assertThatThrownBy(() -> service.bindByCode(1L, "mock", "c", null))
			.hasMessageContaining("已过期或已经用过");
		verify(stateCache, never()).takePurpose(" ");
		verify(authRequest, never()).login(any(AuthCallback.class));
	}

	@Test
	void ownBindStateBindsAndBurnsJustAuthState() {
		when(stateCache.takePurpose("s1")).thenReturn("bind:mock:1");

		service.bindByCode(1L, "mock", "c", "s1");

		ArgumentCaptor<SysUserOauth> row = ArgumentCaptor.forClass(SysUserOauth.class);
		verify(oauthMapper).insert(row.capture());
		assertThat(row.getValue().getUserId()).isEqualTo(1L);
		assertThat(row.getValue().getOpenId()).isEqualTo("open-1");
		verify(stateCache).remove("s1");
	}

	@Test
	void secondAccountOfSameSourceRejected() {
		when(stateCache.takePurpose("s1")).thenReturn("bind:mock:1");
		when(oauthMapper.selectCountByQuery(any(QueryWrapper.class))).thenReturn(1L);

		assertThatThrownBy(() -> service.bindByCode(1L, "mock", "c", "s1"))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("已经绑定了一个这个来源的账号");
		verify(oauthMapper, never()).insert(any(SysUserOauth.class));
	}

	@Test
	void renderRecordsPurposeForTheIssuedState() {
		when(authRequest.authorize(anyString())).thenReturn("https://idp.test/authorize");

		assertThat(service.renderBindUrl("Mock", 7L)).isEqualTo("https://idp.test/authorize");
		ArgumentCaptor<String> issued = ArgumentCaptor.forClass(String.class);
		verify(authRequest).authorize(issued.capture());
		verify(stateCache).markPurpose(issued.getValue(), "bind:mock:7");

		service.renderAuthUrl("mock");
		verify(stateCache).markPurpose(anyString(), eq("login:mock"));
	}

	@Test
	void unbindRemovesEveryRowOfTheSource() {
		service.unbind(1L, "mock");

		verify(oauthMapper).deleteByQuery(any(QueryWrapper.class));
		verify(oauthMapper, never()).selectOneByQuery(any(QueryWrapper.class));
	}
}
