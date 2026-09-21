package com.mugsun.boot.social;

import com.mugsun.core.tool.exception.ServiceException;
import me.zhyd.oauth.cache.AuthStateCache;
import me.zhyd.oauth.config.AuthConfig;
import me.zhyd.oauth.request.AuthGiteeRequest;
import me.zhyd.oauth.request.AuthGithubRequest;
import me.zhyd.oauth.request.AuthQqRequest;
import me.zhyd.oauth.request.AuthRequest;
import me.zhyd.oauth.request.AuthWeChatOpenRequest;
import org.springframework.stereotype.Component;

/**
 * 社交登录 provider 工厂：按来源码构建 JustAuth AuthRequest，统一注入 Redis state 缓存。
 * mock 走本地 AuthMockRequest；真实平台按配置构建（凭证到位即生效，骨架不变）。
 */
@Component
public class SocialAuthFactory {

	private final SocialProperties properties;
	private final AuthStateCache stateCache;

	public SocialAuthFactory(SocialProperties properties, AuthStateCache stateCache) {
		this.properties = properties;
		this.stateCache = stateCache;
	}

	public AuthRequest getAuthRequest(String source) {
		// 总开关：社交登录未启用时一切来源拒绝（修复 enabled 死开关）
		if (!properties.isEnabled()) {
			throw new ServiceException("第三方登录未启用");
		}
		String key = source == null ? "" : source.toLowerCase();
		SocialProperties.ClientConfig cfg = properties.getType().get(key);
		if (cfg == null) {
			throw new ServiceException("不支持或未配置的第三方登录来源：" + source);
		}
		if ("mock".equals(key)) {
			// mock provider 仅 dev 联调：身份固定，生产开启即后门，独立开关缺省关闭
			if (!properties.isMockEnabled()) {
				throw new ServiceException("mock 社交来源未启用");
			}
			AuthConfig authConfig = AuthConfig.builder()
				.clientId(cfg.getClientId())
				.clientSecret(cfg.getClientSecret())
				.redirectUri(cfg.getRedirectUri())
				.ignoreCheckRedirectUri(true)
				.build();
			return new AuthMockRequest(authConfig, stateCache, properties.getMockAuthorizeUrl());
		}
		AuthConfig authConfig = AuthConfig.builder()
			.clientId(cfg.getClientId())
			.clientSecret(cfg.getClientSecret())
			.redirectUri(cfg.getRedirectUri())
			.scopes(cfg.getScopes())
			.build();
		return switch (key) {
			case "github" -> new AuthGithubRequest(authConfig, stateCache);
			case "gitee" -> new AuthGiteeRequest(authConfig, stateCache);
			case "qq" -> new AuthQqRequest(authConfig, stateCache);
			case "wechat_open" -> new AuthWeChatOpenRequest(authConfig, stateCache);
			default -> throw new ServiceException("暂不支持的第三方登录来源：" + source);
		};
	}
}
