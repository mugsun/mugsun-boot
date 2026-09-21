package com.mugsun.boot.security;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * JSON 请求体 XSS 净化：为所有 String 字段注册净化反序列化器，@RequestBody 反序列化即按富文本白名单净化
 * （去 script/事件/危险协议，保留安全标签），防存储型 XSS。query/form 参数由 {@link XssFilter} 严格净化。
 */
@Configuration
public class XssJacksonConfig {

	@Bean
	public Jackson2ObjectMapperBuilderCustomizer xssStringDeserializer() {
		return builder -> builder.deserializerByType(String.class, new JsonDeserializer<String>() {
			@Override
			public String deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
				return XssCleaner.cleanRich(p.getValueAsString());
			}
		});
	}
}
