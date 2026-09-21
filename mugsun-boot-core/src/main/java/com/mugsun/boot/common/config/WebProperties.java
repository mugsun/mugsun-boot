package com.mugsun.boot.common.config;

import com.mugsun.boot.oauth.OAuthConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 管理端入口：OAuth 同意页等需要「后端 302 到前端」的绝对地址。
 * 日常 3006、e2e 3007、反代域名一律走 {@code MUGSUN_FRONT_URL}，禁止代码写死 origin。
 */
@Component
@ConfigurationProperties(prefix = "mugsun.web")
public class WebProperties {

	/** 管理端 origin，例如 {@code http://localhost:3006} 或 {@code https://admin.example.com} */
	private String frontUrl = "http://localhost:3006";

	public String getFrontUrl() {
		return frontUrl;
	}

	public void setFrontUrl(String frontUrl) {
		this.frontUrl = frontUrl;
	}

	/** OAuth 授权端点 302 目标：{@code front-url + /#/oauth-consent} */
	public String oauthConsentPage() {
		String base = frontUrl == null ? "" : frontUrl.trim();
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		if (base.isEmpty()) {
			throw new IllegalStateException("未配置 mugsun.web.front-url（环境变量 MUGSUN_FRONT_URL），无法跳转 OAuth 同意页");
		}
		if (!base.startsWith("http://") && !base.startsWith("https://")) {
			throw new IllegalStateException("mugsun.web.front-url 必须是 http/https 绝对地址：" + base);
		}
		return base + OAuthConstants.CONSENT_PATH;
	}
}
