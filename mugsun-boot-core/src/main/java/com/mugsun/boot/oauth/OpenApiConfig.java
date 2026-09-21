package com.mugsun.boot.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 开放接口守卫装配：对 /open/** 注册令牌 + scope 拦截器。
 */
@Configuration
public class OpenApiConfig implements WebMvcConfigurer {

	private final OAuthService oauthService;
	private final OAuthLogService logService;
	private final ObjectMapper objectMapper;
	private final OpenApiSignService signService;

	public OpenApiConfig(OAuthService oauthService, OAuthLogService logService, ObjectMapper objectMapper,
						 OpenApiSignService signService) {
		this.oauthService = oauthService;
		this.logService = logService;
		this.objectMapper = objectMapper;
		this.signService = signService;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new OpenApiInterceptor(oauthService, logService, objectMapper, signService))
			.addPathPatterns("/open/**");
	}
}
