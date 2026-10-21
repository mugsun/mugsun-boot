package com.mugsun.boot.common.env;

import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 生产环境判定（安全官定的最终规则）：只有显式设置了 {@code spring.profiles.active}、非空，
 * 且含 dev、test 或 local、不含 prod，才算非生产；其余一律按生产处理。
 * <p>不用 {@code acceptsProfiles}：只配 {@code spring.profiles.default=dev} 不算开发环境。
 * <p>最小共用实现：SecuritySelfCheck 合入 main 后应改为复用同一处判定。
 */
public final class DeployProfiles {

	private static final Set<String> NON_PROD = Set.of("dev", "test", "local");

	private DeployProfiles() {
	}

	public static boolean isNonProd(Environment env) {
		return isNonProd(env.getProperty("spring.profiles.active"));
	}

	public static boolean isNonProd(String active) {
		if (active == null || active.isBlank()) {
			return false;
		}
		Set<String> set = Arrays.stream(active.split(","))
			.map(s -> s.trim().toLowerCase(Locale.ROOT))
			.filter(s -> !s.isEmpty())
			.collect(Collectors.toSet());
		return !set.contains("prod") && set.stream().anyMatch(NON_PROD::contains);
	}
}
