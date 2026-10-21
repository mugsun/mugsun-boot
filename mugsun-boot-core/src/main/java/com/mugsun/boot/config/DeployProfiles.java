package com.mugsun.boot.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.Set;

/**
 * 部署环境判定（启动守卫共用）：只有显式激活 dev / test / local（严格小写）才算非生产，其余一律按生产处理。
 * <ul>
 *   <li>没配任何 profile：按生产处理（防止裸跑 jar 绕过守卫）</li>
 *   <li>staging、PROD、production、Dev 等任何其他写法：按生产处理</li>
 *   <li>同时激活 prod 与 dev：按生产处理</li>
 *   <li>没有激活 profile 时看 {@code spring.profiles.default}：default=dev 放行，default=prod 拦</li>
 * </ul>
 * 白名单是常量，刻意不做成配置项。
 */
public final class DeployProfiles {

	/** 非生产 profile 白名单（严格小写匹配） */
	public static final Set<String> NON_PRODUCTION = Set.of("dev", "test", "local");

	private DeployProfiles() {
	}

	/**
	 * 是否按生产环境对待。用 {@link Environment#acceptsProfiles(Profiles)} 判定，
	 * 没有激活 profile 时会回落到 {@code spring.profiles.default}（只设 default=prod 也按生产）。
	 */
	public static boolean isProduction(Environment environment) {
		if (environment.acceptsProfiles(Profiles.of("prod"))) {
			return true;
		}
		return !environment.acceptsProfiles(Profiles.of(NON_PRODUCTION.toArray(String[]::new)));
	}
}
