package com.mugsun.boot.oauth;

/**
 * OAuth2 协议侧常量：同意页路径与前端路由契约（hash 模式）。
 */
public final class OAuthConstants {

	private OAuthConstants() {
	}

	/** 管理端同意页 SPA 路由，须与 {@code mugsun-pc} staticRoutes 一致 */
	public static final String CONSENT_PATH = "/#/oauth-consent";
}
