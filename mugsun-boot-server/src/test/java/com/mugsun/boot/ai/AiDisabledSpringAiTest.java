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
			Class<?> type = context.getBeanFactory().getType(name, false);
			String typeName = type == null ? "" : type.getName();
			if (typeName.startsWith("org.springframework.ai.") || typeName.startsWith("io.modelcontextprotocol.")
				|| name.startsWith("org.springframework.ai.")) {
				found.add(name + " : " + typeName);
			}
		}
		assertThat(found).isEmpty();
	}

	@Test
	@DisplayName("MCP Server 的 SSE 与消息端点都是 404")
	void noMcpEndpoints() {
		assertThat(get("/sse", null).getStatusCode().value()).isEqualTo(404);
		assertThat(post("/mcp/message", Map.of(), null).getStatusCode().value()).isEqualTo(404);
		assertThat(post("/mcp", Map.of(), null).getStatusCode().value()).isEqualTo(404);
	}
}
