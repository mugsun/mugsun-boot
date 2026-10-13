package com.mugsun.boot.ai.support;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 将 mugsun.openapi.trusted-proxies 注入 {@link OpenApiGate}。 */
@Component
public class OpenApiTrustedProxies {

	@Value("${mugsun.openapi.trusted-proxies:}")
	private String trustedProxies;

	@PostConstruct
	void init() {
		OpenApiGate.setTrustedProxies(trustedProxies);
	}
}
