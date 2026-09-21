package com.mugsun.boot.social;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 社交登录配置：各来源 OAuth 应用凭证（clientId/secret/redirectUri）。
 * key = 来源码（mock/github/gitee/qq/wechat_open…），yaml 起步，真实凭证按环境注入。
 */
@Component
@ConfigurationProperties(prefix = "mugsun.social")
public class SocialProperties {

	/** 社交登录总开关 */
	private boolean enabled = true;

	/** mock provider 开关（仅 dev 联调，生产必须 false——mock 身份固定，开启即后门） */
	private boolean mockEnabled = false;

	/** 本地 mock provider 的“授权页”地址（后端 mock-authorize 端点，dev 联调用） */
	private String mockAuthorizeUrl = "http://localhost:8080/auth/social/mock-authorize";

	/** 各来源配置 */
	private Map<String, ClientConfig> type = new LinkedHashMap<>();

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public boolean isMockEnabled() {
		return mockEnabled;
	}

	public void setMockEnabled(boolean mockEnabled) {
		this.mockEnabled = mockEnabled;
	}

	public String getMockAuthorizeUrl() {
		return mockAuthorizeUrl;
	}

	public void setMockAuthorizeUrl(String mockAuthorizeUrl) {
		this.mockAuthorizeUrl = mockAuthorizeUrl;
	}

	public Map<String, ClientConfig> getType() {
		return type;
	}

	public void setType(Map<String, ClientConfig> type) {
		this.type = type;
	}

	public static class ClientConfig {
		private String clientId;
		private String clientSecret;
		private String redirectUri;
		private List<String> scopes;

		public String getClientId() {
			return clientId;
		}

		public void setClientId(String clientId) {
			this.clientId = clientId;
		}

		public String getClientSecret() {
			return clientSecret;
		}

		public void setClientSecret(String clientSecret) {
			this.clientSecret = clientSecret;
		}

		public String getRedirectUri() {
			return redirectUri;
		}

		public void setRedirectUri(String redirectUri) {
			this.redirectUri = redirectUri;
		}

		public List<String> getScopes() {
			return scopes;
		}

		public void setScopes(List<String> scopes) {
			this.scopes = scopes;
		}
	}
}
