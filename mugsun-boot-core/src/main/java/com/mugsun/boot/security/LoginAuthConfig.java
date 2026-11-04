package com.mugsun.boot.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册全局登录拦截器（#93）：早于强制改密守卫，覆盖 {@code /**}。
 */
@Configuration
public class LoginAuthConfig implements WebMvcConfigurer {

	/** 略早于 {@link ForceChangePasswordConfig}（HIGHEST+5），先判定登录 */
	public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 3;

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new LoginAuthInterceptor())
			.addPathPatterns("/**")
			.order(ORDER);
	}
}
