package com.mugsun.boot.social;

import me.zhyd.oauth.cache.AuthStateCache;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.model.AuthCallback;
import me.zhyd.oauth.model.AuthToken;
import me.zhyd.oauth.model.AuthUser;
import me.zhyd.oauth.request.AuthDefaultRequest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 本地 mock 第三方授权请求：继承 JustAuth AuthDefaultRequest 以复用其 login() 的
 * checkCode + checkState（Redis CSRF 校验）机制；覆写 getAccessToken/getUserInfo 用
 * 确定性数据模拟第三方，不发起真实 HTTP。openId 由 code 派生（仍属服务端换取，客户端不可伪造）。
 */
public class AuthMockRequest extends AuthDefaultRequest {

	private static final String TOKEN_PREFIX = "mock-token-";

	/** 固定的 mock 第三方身份：保证“未绑定拒绝→绑定→登录成功”全程用同一 openId（真实平台同用户 openId 亦稳定） */
	private static final String MOCK_OPEN_ID = "mock_default_user";

	private final String authorizeBaseUrl;

	public AuthMockRequest(AuthConfig config, AuthStateCache authStateCache, String authorizeBaseUrl) {
		super(config, MockAuthSource.MOCK, authStateCache);
		this.authorizeBaseUrl = authorizeBaseUrl;
	}

	/** 构建“授权页”跳转地址；同时缓存 state 供回调 checkState 校验（对齐 JustAuth getRealState 行为）。
	 *  redirect_uri 显式 URL 编码——前端 hash 路由含 # ，不编码会被浏览器当作 fragment 丢失。 */
	@Override
	public String authorize(String state) {
		authStateCache.cache(state, state);
		return authorizeBaseUrl
			+ "?response_type=code"
			+ "&client_id=" + URLEncoder.encode(config.getClientId(), StandardCharsets.UTF_8)
			+ "&redirect_uri=" + URLEncoder.encode(config.getRedirectUri(), StandardCharsets.UTF_8)
			+ "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
	}

	@Override
	public AuthToken getAccessToken(AuthCallback authCallback) {
		return AuthToken.builder().accessToken(TOKEN_PREFIX + authCallback.getCode()).build();
	}

	@Override
	public AuthUser getUserInfo(AuthToken authToken) {
		return AuthUser.builder()
			.uuid(MOCK_OPEN_ID)
			.username(MOCK_OPEN_ID)
			.nickname("模拟第三方用户")
			.source(MockAuthSource.MOCK.getName())
			.token(authToken)
			.build();
	}
}
