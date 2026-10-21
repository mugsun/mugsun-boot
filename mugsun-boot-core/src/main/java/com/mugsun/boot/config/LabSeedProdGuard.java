package com.mugsun.boot.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 生产联调账号 fail-fast 守卫：prod profile 下开启 {@code mugsun.lab.seed-fronttest} 即拒绝启动。
 * <p>该开关会播种口令为 123456 的低权账号 fronttest，仅供开发联调与 e2e；
 * 生产误开等于公开弱口令账号。判定方式与 {@link InitPasswordHardeningGuard} 一致（显式 prod profile），
 * 先于所有播种 Runner 执行（{@link Order} 最小值）。
 */
@Component
@Order(Integer.MIN_VALUE)
public class LabSeedProdGuard implements CommandLineRunner {

	static final String KEY = "mugsun.lab.seed-fronttest";

	private final Environment environment;

	public LabSeedProdGuard(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void run(String... args) {
		boolean prod = false;
		for (String profile : environment.getActiveProfiles()) {
			if ("prod".equals(profile)) {
				prod = true;
				break;
			}
		}
		if (!prod) {
			return;
		}
		if (Boolean.TRUE.equals(environment.getProperty(KEY, Boolean.class, false))) {
			throw new IllegalStateException(
				"生产环境禁止开启 " + KEY + "（会播种口令为 123456 的 fronttest 账号），请删除该配置或设为 false 后再启动");
		}
	}
}
