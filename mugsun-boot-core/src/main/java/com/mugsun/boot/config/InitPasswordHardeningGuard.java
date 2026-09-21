package com.mugsun.boot.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 生产初始口令 fail-fast 守卫：prod profile 下必须显式注入初始口令
 * （环境变量 MUGSUN_INIT_PASSWORD 或配置 mugsun.security.init-password），仍在用内置默认口令即拒绝启动。
 * <p>默认口令属等保高危项；初始口令除种子 admin 外，还用于新租户管理员、重置密码、导入用户，
 * 故无论 admin 是否已存在，prod 一律强制注入。先于所有播种 Runner 执行（{@link Order} 最小值）。
 */
@Component
@Order(Integer.MIN_VALUE)
public class InitPasswordHardeningGuard implements CommandLineRunner {

	private final Environment environment;

	public InitPasswordHardeningGuard(Environment environment) {
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
		boolean injected = environment.getProperty("mugsun.security.init-password") != null
			|| environment.getProperty("MUGSUN_INIT_PASSWORD") != null;
		if (!injected) {
			throw new IllegalStateException(
				"生产环境必须注入初始口令（环境变量 MUGSUN_INIT_PASSWORD 或配置 mugsun.security.init-password），禁止使用内置默认口令启动");
		}
	}
}
