package com.mugsun.boot.common.web;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 将 mugsun.openapi.trusted-proxies 注入 {@link ClientIpResolver}。 */
@Component
public class TrustedProxyConfig {

	@Value("${mugsun.openapi.trusted-proxies:}")
	private String trustedProxies;

	@PostConstruct
	void init() {
		ClientIpResolver.setTrustedProxies(trustedProxies);
	}
}
