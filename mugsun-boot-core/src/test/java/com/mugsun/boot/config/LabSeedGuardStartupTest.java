package com.mugsun.boot.config;

import com.mugsun.boot.security.SecurityPolicyService;
import com.mugsun.boot.system.mapper.SysMenuMapper;
import com.mugsun.boot.system.mapper.SysRoleMapper;
import com.mugsun.boot.system.mapper.SysRoleMenuMapper;
import com.mugsun.boot.system.mapper.SysTenantMapper;
import com.mugsun.boot.system.mapper.SysUserMapper;
import com.mugsun.boot.system.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 真实 SpringApplication 启动流程：生产 + seed-fronttest=true 时守卫先于 DataInitializer 抛出，
 * 播种一次都没执行（用户、角色等 mapper 零交互），证明不会建出联调账号。
 */
class LabSeedGuardStartupTest {

	/** 每个用例自己的 mock，经 initializer 注册进上下文，避免全量跑时静态 mock 串用 */
	record Mocks(SysUserMapper users, SysRoleMapper roles, SysMenuMapper menus,
				 SysUserRoleMapper userRoles, SysRoleMenuMapper roleMenus, SysTenantMapper tenants) {
		static Mocks create() {
			return new Mocks(mock(SysUserMapper.class), mock(SysRoleMapper.class), mock(SysMenuMapper.class),
				mock(SysUserRoleMapper.class), mock(SysRoleMenuMapper.class), mock(SysTenantMapper.class));
		}
	}

	/**
	 * 刻意不加 @Configuration：本类在 com.mugsun.boot 包下，加了会被集成测试的组件扫描收进真实应用上下文，
	 * 多出一个用 mock PasswordEncoder 的 DataInitializer，导致管理员登录失败（全量跑 24 失败 23 错误的根因）。
	 * 只作为 SpringApplicationBuilder 的 source 显式传入（@Bean lite 模式）。
	 */
	static class Cfg {
		@Bean
		LabSeedProdGuard labSeedProdGuard(Environment env) {
			return new LabSeedProdGuard(env);
		}

		@Bean
		DataInitializer dataInitializer(Mocks m) {
			return new DataInitializer(m.users(), m.roles(), m.menus(), m.userRoles(), m.roleMenus(),
				mock(PasswordEncoder.class), mock(SecurityPolicyService.class), m.tenants());
		}
	}

	@Test
	void prodWithSeedOnFailsBeforeAnySeeding() {
		Mocks m = Mocks.create();
		assertThatThrownBy(() -> new SpringApplicationBuilder(Cfg.class)
			.web(WebApplicationType.NONE)
			.profiles("prod")
			.initializers(ctx -> ctx.getBeanFactory().registerSingleton("mocks", m))
			.properties(LabSeedProdGuard.KEY + "=true", "mugsun.security.init-password=x",
				"spring.main.banner-mode=off")
			.run().close())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(LabSeedProdGuard.KEY);
		verifyNoInteractions(m.users(), m.roles(), m.menus(), m.userRoles(), m.roleMenus(), m.tenants());
	}
}
