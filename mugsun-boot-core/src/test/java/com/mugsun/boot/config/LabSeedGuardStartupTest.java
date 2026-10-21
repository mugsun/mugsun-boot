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
import org.springframework.context.annotation.Configuration;
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

	static final SysUserMapper USERS = mock(SysUserMapper.class);
	static final SysRoleMapper ROLES = mock(SysRoleMapper.class);
	static final SysMenuMapper MENUS = mock(SysMenuMapper.class);
	static final SysUserRoleMapper USER_ROLES = mock(SysUserRoleMapper.class);
	static final SysRoleMenuMapper ROLE_MENUS = mock(SysRoleMenuMapper.class);
	static final SysTenantMapper TENANTS = mock(SysTenantMapper.class);

	@Configuration(proxyBeanMethods = false)
	static class Cfg {
		@Bean
		LabSeedProdGuard labSeedProdGuard(Environment env) {
			return new LabSeedProdGuard(env);
		}

		@Bean
		DataInitializer dataInitializer() {
			return new DataInitializer(USERS, ROLES, MENUS, USER_ROLES, ROLE_MENUS,
				mock(PasswordEncoder.class), mock(SecurityPolicyService.class), TENANTS);
		}
	}

	@Test
	void prodWithSeedOnFailsBeforeAnySeeding() {
		assertThatThrownBy(() -> new SpringApplicationBuilder(Cfg.class)
			.web(WebApplicationType.NONE)
			.profiles("prod")
			.properties(LabSeedProdGuard.KEY + "=true", "mugsun.security.init-password=x",
				"spring.main.banner-mode=off")
			.run().close())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(LabSeedProdGuard.KEY);
		verifyNoInteractions(USERS, ROLES, MENUS, USER_ROLES, ROLE_MENUS, TENANTS);
	}
}
