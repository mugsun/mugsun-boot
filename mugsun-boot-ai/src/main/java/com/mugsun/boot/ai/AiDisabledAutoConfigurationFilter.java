package com.mugsun.boot.ai;

import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationMetadata;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * {@code mugsun.ai.enabled=false} 时连同 Spring AI 自身的自动装配一起关掉。
 * <p>#85 只把 {@code com.mugsun.boot.ai} 包排除在扫描外；但 Spring AI 的 starter（OpenAI、pgvector、MCP Client/Server）
 * 各自带 {@code AutoConfiguration}，不受本项目开关控制。比如 {@code MUGSUN_AI_MCP_SERVER=true} 时，
 * 即使 AI 已关，MCP Server 仍会注册 {@code /sse}、{@code /mcp/message} 端点并对外暴露。
 * <p>本过滤器在自动装配导入阶段把 {@code org.springframework.ai.} 下的全部自动装配类剔除，
 * 不必逐个维护类名，Spring AI 升级新增的自动装配也会一并关掉。AI 开启（缺省）时不做任何过滤。
 * <p>开关判定见 {@link #aiEnabled(Environment)}，与 {@link AiAutoConfiguration} 的条件逐字一致。
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
		boolean aiEnabled = aiEnabled(environment);
		boolean[] result = new boolean[autoConfigurationClasses.length];
		for (int i = 0; i < autoConfigurationClasses.length; i++) {
			String name = autoConfigurationClasses[i];
			result[i] = aiEnabled || name == null || !name.startsWith(SPRING_AI_PREFIX);
		}
		return result;
	}

	/**
	 * 与 {@link AiAutoConfiguration} 的 {@code @ConditionalOnProperty(havingValue = "true", matchIfMissing = true)} 判定完全一致：
	 * 缺省视为开启，否则只有不区分大小写的 {@code "true"} 才算开启；{@code 1}、{@code on}、{@code yes}、拼写错误等一律算关闭，
	 * 避免「AI 自身已关、Spring AI 自动装配却放行」的不一致（B88-1）。
	 * <p>读不到配置（{@code environment} 未注入）时按关闭处理：宁可少装 Spring AI，也不放行。
	 */
	static boolean aiEnabled(Environment environment) {
		if (environment == null) {
			return false;
		}
		return "true".equalsIgnoreCase(environment.getProperty("mugsun.ai.enabled", "true"));
	}
}
