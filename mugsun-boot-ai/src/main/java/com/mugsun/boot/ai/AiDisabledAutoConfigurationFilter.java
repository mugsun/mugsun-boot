package com.mugsun.boot.ai;

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * {@code mugsun.ai.enabled=false} 时连同 Spring AI 自身的自动装配一起关掉。
 * <p>#85 只把 {@code com.mugsun.boot.ai} 包排除在扫描外；但 Spring AI 的 starter（OpenAI、pgvector、MCP Client/Server）
 * 各自带 {@code AutoConfiguration}，不受本项目开关控制。比如 {@code MUGSUN_AI_MCP_SERVER=true} 时，
 * 即使 AI 已关，MCP Server 仍会注册 {@code /sse}、{@code /mcp/message} 端点并对外暴露。
 * <p>本过滤器在自动装配导入阶段把 {@code org.springframework.ai.} 下的全部自动装配类剔除，
 * 不必逐个维护类名，Spring AI 升级新增的自动装配也会一并关掉。AI 开启（缺省）时不做任何过滤。
 */
public class AiDisabledAutoConfigurationFilter implements AutoConfigurationImportFilter, EnvironmentAware {

	/** Spring AI 自动装配类所在的包前缀 */
	static final String SPRING_AI_PREFIX = "org.springframework.ai.";

	private Environment environment;

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	@Override
	public boolean[] match(String[] autoConfigurationClasses, AutoConfigurationMetadata autoConfigurationMetadata) {
		boolean aiEnabled = environment == null
			|| Binder.get(environment).bind("mugsun.ai.enabled", Boolean.class).orElse(Boolean.TRUE);
		boolean[] result = new boolean[autoConfigurationClasses.length];
		for (int i = 0; i < autoConfigurationClasses.length; i++) {
			String name = autoConfigurationClasses[i];
			result[i] = aiEnabled || name == null || !name.startsWith(SPRING_AI_PREFIX);
		}
		return result;
	}
}
