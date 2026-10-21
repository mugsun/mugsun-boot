package com.mugsun.boot.config;

import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 部署环境判定（启动守卫共用）：只看显式激活的 profile（spring.profiles.active 等），
 * 不看 spring.profiles.default。
 * <ul>
 *   <li>active 非空、含 dev / test / local 之一、且不含 prod 时才算非生产</li>
 *   <li>profile 名先 trim 再转小写比较（DEV、" Test " 都算）</li>
 *   <li>active 为空（包括只配了 default=dev）、staging、production 等：一律按生产处理</li>
 * </ul>
 * 白名单是常量，刻意不做成配置项。
 */
public final class DeployProfiles {

	/** 非生产 profile 白名单（trim + 小写后匹配） */
	public static final Set<String> NON_PRODUCTION = Set.of("dev", "test", "local");

	private DeployProfiles() {
	}

	/** 是否按生产环境对待。刻意不用 acceptsProfiles，避免回落到 spring.profiles.default。 */
	public static boolean isProduction(Environment environment) {
		List<String> active = Arrays.stream(environment.getActiveProfiles())
			.map(p -> p == null ? "" : p.trim().toLowerCase(Locale.ROOT))
			.filter(p -> !p.isEmpty())
			.toList();
		if (active.isEmpty() || active.contains("prod")) {
			return true;
		}
		return active.stream().noneMatch(NON_PRODUCTION::contains);
	}
}
