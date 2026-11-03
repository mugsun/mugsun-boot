package com.mugsun.boot.security;

import com.mugsun.boot.system.mapper.SysUserMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 首次登录强制改密守卫：全局注册（登录判定之后），在租户守卫之前即可。
 */
@Configuration
public class ForceChangePasswordConfig implements WebMvcConfigurer {

	private final SysUserMapper userMapper;

	public ForceChangePasswordConfig(SysUserMapper userMapper) {
		this.userMapper = userMapper;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new ForceChangePasswordInterceptor(userMapper))
			.addPathPatterns("/**")
			.order(Ordered.HIGHEST_PRECEDENCE + 5);
	}
}
