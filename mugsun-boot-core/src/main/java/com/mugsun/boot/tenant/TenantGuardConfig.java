package com.mugsun.boot.tenant;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 租户守卫装配：注册请求级租户守卫拦截器（生命周期/一致性/套餐强制），最高优先级先于数据权限运行。
 */
@Configuration
public class TenantGuardConfig implements WebMvcConfigurer {

	private final TenantValidator validator;

	public TenantGuardConfig(TenantValidator validator) {
		this.validator = validator;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new TenantGuardInterceptor(validator))
			.addPathPatterns("/system/**")
			.order(Ordered.HIGHEST_PRECEDENCE + 10);
	}
}
