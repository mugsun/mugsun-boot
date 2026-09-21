package com.mugsun.boot.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档信息：SpringDoc 自动扫描控制器生成 /v3/api-docs，供前端 openapi-typescript 生成契约类型。
 */
@Configuration
public class OpenApiDocConfig {

	@Bean
	public OpenAPI mugsunOpenAPI() {
		return new OpenAPI().info(new Info()
			.title("Mugsun API")
			.version("1.0")
			.description("Mugsun 平台接口文档（供前端 openapi 类型生成）"));
	}
}
