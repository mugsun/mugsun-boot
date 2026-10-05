package com.mugsun.boot.common.perm;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 接口权限守卫：覆盖全部接口，只有命中已启用规则时才额外校验。
 */
@Configuration
public class ApiScopeGuardConfig implements WebMvcConfigurer {

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new ApiScopeGuard())
			.addPathPatterns("/**")
			.order(Ordered.HIGHEST_PRECEDENCE + 25);
	}
}
