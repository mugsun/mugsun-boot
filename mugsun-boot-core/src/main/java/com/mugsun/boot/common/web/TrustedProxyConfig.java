package com.mugsun.boot.common.web;

import com.mugsun.boot.demo.DemoConstants;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 将 mugsun.openapi.trusted-proxies 注入 {@link ClientIpResolver}。 */
@Component
public class TrustedProxyConfig {

	private static final Logger log = LoggerFactory.getLogger(TrustedProxyConfig.class);

	@Value("${mugsun.openapi.trusted-proxies:}")
	private String trustedProxies;

	@Value("${" + DemoConstants.ENABLED_PROPERTY + ":false}")
	private boolean demo;

	/** 演示默认值：只信任回环 */
	static final String DEMO_DEFAULT = "127.0.0.0/8,::1/128";

	/** 空，或等于只信任回环的默认值，都算没有配置 */
	static boolean unconfigured(String raw) {
		String v = raw == null ? "" : raw.replace(" ", "");
		return IpCidr.parseList(v).isEmpty() || DEMO_DEFAULT.equals(v);
	}

	@PostConstruct
	void init() {
		ClientIpResolver.setTrustedProxies(trustedProxies);
		if (demo && unconfigured(trustedProxies)) {
			// 演示环境按 IP 锁定与限流：反向代理后面不配可信代理，所有访客都是代理的 IP，一人输错锁所有人
			log.warn("[demo] 未配置可信代理（MUGSUN_OPENAPI_TRUSTED_PROXIES，当前只信任回环）：反向代理不在本机时，"
				+ "所有访客共用代理 IP，按 IP 的登录锁定和限流会互相影响；请设成反向代理的地址或网段");
		}
	}
}
