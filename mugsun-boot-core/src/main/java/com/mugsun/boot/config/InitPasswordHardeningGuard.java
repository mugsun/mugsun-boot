package com.mugsun.boot.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 生产初始口令 fail-fast 守卫：非开发环境（见 {@link DeployProfiles}，没配 profile 也算生产）必须显式注入
 * 平台种子 admin 口令（环境变量 MUGSUN_INIT_PASSWORD 或配置 mugsun.security.init-password），
 * 仍在用内置默认口令即拒绝启动。
 * <p>该口令仅用于首次播种平台 admin 与 init show；重置口令、新建租户管理员、导入用户已改为各自
 * 随机一次性口令（见 {@code SecurityPolicyService#generateOneTimePassword}）。无论 admin 是否已存在，
 * 生产一律强制注入。先于所有播种 Runner 执行（{@link Order} 最小值）。
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
		if (!DeployProfiles.isProduction(environment)) {
			return;
		}
		boolean injected = environment.getProperty("mugsun.security.init-password") != null
			|| environment.getProperty("MUGSUN_INIT_PASSWORD") != null;
		if (!injected) {
			throw new IllegalStateException(
				"生产环境必须注入初始口令（环境变量 MUGSUN_INIT_PASSWORD 或配置 mugsun.security.init-password），禁止使用内置默认口令启动；本地自测请显式激活 dev profile");
		}
	}
}
