package com.mugsun.boot.common.perm;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 权限兜底守卫装配：注册 {@link PermissionGuardInterceptor} 于 /system/**（在租户守卫之后、数据权限之前）。
 */
@Configuration
public class PermissionGuardConfig implements WebMvcConfigurer {

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new PermissionGuardInterceptor())
			.addPathPatterns("/system/**")
			.order(Ordered.HIGHEST_PRECEDENCE + 20);
	}
}
