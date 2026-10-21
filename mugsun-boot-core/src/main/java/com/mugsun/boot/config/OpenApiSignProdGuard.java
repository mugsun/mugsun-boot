package com.mugsun.boot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 生产 fail-fast 守卫：开放接口签名（sign-required=false 时不验签、不防重放，只校验令牌）。
 * <p>非开发环境（见 {@link DeployProfiles}，没配 profile 也算生产）把 {@code mugsun.open-api.sign-required} 设为 false 即拒绝启动；
 * 唯一豁免是显式设置 {@code mugsun.open-api.i-understand-unsigned-requests-are-insecure=true}，启动时打 WARN。
 * 写法与 {@link InitPasswordHardeningGuard} 一致，先于所有播种 Runner 执行。
 */
@Component
@Order(Integer.MIN_VALUE)
public class OpenApiSignProdGuard implements CommandLineRunner {

	static final String KEY = "mugsun.open-api.sign-required";
	static final String EXEMPT_KEY = "mugsun.open-api.i-understand-unsigned-requests-are-insecure";

	private static final Logger log = LoggerFactory.getLogger(OpenApiSignProdGuard.class);

	private final Environment environment;

	public OpenApiSignProdGuard(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void run(String... args) {
		if (!DeployProfiles.isProduction(environment)) {
			return;
		}
		if (!Boolean.FALSE.equals(environment.getProperty(KEY, Boolean.class, true))) {
			return;
		}
		if (Boolean.TRUE.equals(environment.getProperty(EXEMPT_KEY, Boolean.class, false))) {
			log.warn("生产环境关闭开放接口签名（{}=false），已由 {}=true 豁免。这是不安全的配置，请尽快恢复", KEY, EXEMPT_KEY);
			return;
		}
		throw new IllegalStateException("生产环境禁止把 " + KEY + " 设为 false。请删除该配置或设为 true；"
			+ "确需关闭请同时设置 " + EXEMPT_KEY + "=true（不安全）；本地自测请显式激活 dev profile");
	}
}
