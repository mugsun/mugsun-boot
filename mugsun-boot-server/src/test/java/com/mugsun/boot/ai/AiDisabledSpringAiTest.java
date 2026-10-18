package com.mugsun.boot.ai;

import com.mugsun.boot.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全回归（B85-1）：{@code mugsun.ai.enabled=false} 时 Spring AI 自身的自动装配也必须关掉。
 * <p>与 {@link AiDisabledApiTest} 同在 surefire fork ai-disabled-it 里跑（共用同一上下文）；该 fork 额外设置
 * 环境变量 {@code MUGSUN_AI_MCP_SERVER=true}，模拟运维打开了 MCP Server 开关。
 */
class AiDisabledSpringAiTest extends AbstractIntegrationTest {

	static {
		// 与 AiDisabledApiTest 相同的覆盖项：无论哪个类先建上下文，结果都一样
		PROPERTY_OVERRIDES.put("mugsun.ai.enabled", () -> "false");
	}

	@Autowired
	private ConfigurableApplicationContext context;

	@Autowired
	private Environment environment;

	@Test
	@DisplayName("前提：本 fork 确实打开了 MCP Server 开关")
	void mcpServerSwitchIsOn() {
		assertThat(environment.getProperty("spring.ai.mcp.server.enabled")).isEqualTo("true");
		assertThat(environment.getProperty("mugsun.ai.enabled")).isEqualTo("false");
	}

	@Test
	@DisplayName("容器里没有任何 Spring AI / MCP SDK 的 Bean")
	void noSpringAiBeans() {
		List<String> found = new ArrayList<>();
		for (String name : context.getBeanDefinitionNames()) {
			// 上下文已完全启动，允许 FactoryBean 初始化以拿到真实类型；仍解析不出类型的一律计入（fail closed，B88-2）
			Class<?> type = context.getBeanFactory().getType(name);
			String className = context.getBeanFactory().getBeanDefinition(name).getBeanClassName();
			if (type == null) {
				found.add(name + " : <unresolved>");
			} else if (isSpringAi(type.getName()) || isSpringAi(name) || (className != null && isSpringAi(className))) {
				found.add(name + " : " + type.getName());
			}
		}
		assertThat(found).isEmpty();
	}

	@Test
	@DisplayName("前提：Spring AI 的自动装配类确实在 classpath 上（否则「没有 Bean」不说明过滤器生效）")
	void springAiAutoConfigurationsPresent() throws ClassNotFoundException {
		Class.forName("org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration");
		Class.forName("org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration");
	}

	private static boolean isSpringAi(String name) {
		return name.startsWith("org.springframework.ai.") || name.startsWith("io.modelcontextprotocol.");
	}

	@Test
	@DisplayName("MCP Server 的 SSE 与消息端点都是 404")
	void noMcpEndpoints() {
		assertThat(get("/sse", null).getStatusCode().value()).isEqualTo(404);
		assertThat(post("/mcp/message", Map.of(), null).getStatusCode().value()).isEqualTo(404);
		assertThat(post("/mcp", Map.of(), null).getStatusCode().value()).isEqualTo(404);
	}
}
