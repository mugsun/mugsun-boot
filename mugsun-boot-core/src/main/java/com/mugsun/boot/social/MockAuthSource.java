package com.mugsun.boot.social;

import me.zhyd.oauth.config.AuthSource;
import me.zhyd.oauth.request.AuthDefaultRequest;

/**
 * 本地 mock 社交来源：用于无真实第三方凭证时端到端验证社交登录全链路（授权→回调→换 openId→绑定/登录）。
 * accessToken/userInfo URL 不参与真实 HTTP（AuthMockRequest 覆写 getAccessToken/getUserInfo）。
 */
public enum MockAuthSource implements AuthSource {

	MOCK;

	@Override
	public String authorize() {
		// 授权页地址由 AuthMockRequest.authorize(state) 用配置动态构建，此处占位
		return "mock:authorize";
	}

	@Override
	public String accessToken() {
		return "mock:accessToken";
	}

	@Override
	public String userInfo() {
		return "mock:userInfo";
	}

	@Override
	public Class<? extends AuthDefaultRequest> getTargetClass() {
		return AuthMockRequest.class;
	}

	@Override
	public String getName() {
		return "mock";
	}
}
