package com.mugsun.boot.security;

import java.util.List;
import java.util.Set;

/**
 * 全局登录白名单（#93）：未列于此的入口默认要求 Sa-Token 登录。
 * <p>与 {@link LoginAuthInterceptor}、{@link com.mugsun.boot.LoginEntryGuardApiTest} 共用同一数据源；
 * 变更公开入口时只改本类（或同步补测）。路径比较前由拦截器经
 * {@code UrlPathHelper.getLookupPathForRequest} 归一（与 #94 Actuator 一致）。
 */
public final class LoginPublicPaths {

	private LoginPublicPaths() {
	}

	/**
	 * 精确公开路径（已去尾斜杠）。含登录前认证流、OAuth2 令牌口、探活与文档入口等。
	 */
	public static final Set<String> EXACT = Set.of(
		"/ping",
		"/csp-report",
		"/favicon.ico",
		"/error",
		"/doc.html",
		"/swagger-ui.html",
		// Web 登录前
		"/auth/sm2-public-key",
		"/auth/login",
		"/auth/two-factor",
		"/auth/register",
		"/auth/forget-code",
		"/auth/forget-reset",
		"/auth/sms-code",
		"/auth/sms-login",
		"/auth/social/sources",
		"/auth/social/mock-authorize",
		"/auth/social/login",
		"/auth/logout",
		// App 登录前（logout/me 仍需登录，不在此列）
		"/app/auth/sm2-public-key",
		"/app/auth/login",
		"/app/auth/two-factor",
		// OAuth2 协议口（authorize/info·confirm 需登录）
		"/oauth2/token",
		"/oauth2/introspect",
		"/oauth2/revoke",
		"/oauth2/authorize"
	);

	/**
	 * 前缀公开：path 等于前缀或 {@code 前缀 + "/"} 起头（段感知，不误伤 {@code /openx}）。
	 * <ul>
	 *   <li>{@code /open}：开放 API，由 {@code OpenApiInterceptor} 校验令牌/签名</li>
	 *   <li>{@code /v1}：AI OpenAPI，Bearer sk- 自鉴权</li>
	 *   <li>{@code /file}：本地直出，由 {@code LocalFileAccessFilter} 签名/公开前缀守卫</li>
	 *   <li>{@code /track}：埋点 SDK 采集（管理台在 {@code /system/track}）</li>
	 *   <li>验证码 / 头像 raw / 社交 authorize URL / actuator 探活 / OpenAPI 文档</li>
	 * </ul>
	 */
	public static final List<String> PREFIXES = List.of(
		"/open",
		"/v1",
		"/file",
		"/track",
		"/auth/captcha",
		"/app/captcha",
		"/auth/avatar/raw",
		"/auth/social/render",
		"/actuator/health",
		"/actuator/info",
		"/v3/api-docs",
		"/swagger-ui",
		"/webjars"
	);

	/** 归一后的查找路径是否公开（接受尾斜杠） */
	public static boolean isPublic(String lookupPath) {
		if (lookupPath == null || lookupPath.isEmpty()) {
			return false;
		}
		String path = stripTrailingSlash(lookupPath);
		if (EXACT.contains(path)) {
			return true;
		}
		for (String prefix : PREFIXES) {
			if (path.equals(prefix) || path.startsWith(prefix + "/")) {
				return true;
			}
		}
		return false;
	}

	public static String stripTrailingSlash(String path) {
		if (path.length() > 1 && path.endsWith("/")) {
			return path.substring(0, path.length() - 1);
		}
		return path;
	}
}
