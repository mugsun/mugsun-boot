package com.mugsun.boot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1 对照组：默认（mugsun.ai.enabled=true，full 装配）时 AI 接口已映射，且真正调用成功（HTTP 200 + 成功码），而不只是「不是 404」。与其他集成测试共用默认上下文。
 */
class AiEnabledApiTest extends AbstractIntegrationTest {

	@Autowired
	private ApplicationContext context;

	@Test
	@DisplayName("AI 接口已映射，管理员访问 HTTP 200 且 R.code=200")
	void aiEndpointsMappedAndSucceed() {
		RequestMappingHandlerMapping mapping =
			context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
		List<String> patterns = new ArrayList<>();
		mapping.getHandlerMethods().keySet().forEach(info -> patterns.addAll(info.getPatternValues()));
		assertThat(patterns).contains("/system/ai/model/page");
		ResponseEntity<String> resp = get("/system/ai/model/page", loginAdmin());
		assertThat(resp.getStatusCode().value()).as("HTTP 状态：" + resp.getBody()).isEqualTo(200);
		JsonNode body = readBody(resp);
		assertThat(body.path("code").asInt()).as("R.code：" + body.path("msg").asText()).isEqualTo(200);
		assertThat(body.path("success").asBoolean(true)).isTrue();
		assertThat(body.path("data").isObject()).as("分页数据").isTrue();
	}

	@Autowired
	private org.springframework.core.env.Environment environment;

	@Test
	@DisplayName("B89-0 守卫：内置 MCP Server 暂时下线（加鉴权后恢复）；即使打开 MUGSUN_AI_MCP_SERVER / spring.ai.mcp.server.enabled，/sse、/mcp、/mcp/message 仍是 404（匿名与管理员）")
	void mcpEndpointsAlways404WhenAiOn() {
		assertThat(System.getenv("MUGSUN_AI_MCP_SERVER")).as("前提：本 fork 打开了 MCP Server 开关").isEqualTo("true");
		assertThat(environment.getProperty("spring.ai.mcp.server.enabled")).isEqualTo("true");
		assertThat(environment.getProperty("mugsun.ai.enabled")).isEqualTo("true");
		String token = loginAdmin();
		for (String t : new String[] {null, token}) {
			String who = t == null ? "匿名" : "管理员";
			assertThat(get("/sse", t).getStatusCode().value()).as(who + " GET /sse").isEqualTo(404);
			assertThat(get("/mcp", t).getStatusCode().value()).as(who + " GET /mcp").isEqualTo(404);
			assertThat(post("/mcp", java.util.Map.of(), t).getStatusCode().value()).as(who + " POST /mcp").isEqualTo(404);
			assertThat(post("/mcp/message", java.util.Map.of(), t).getStatusCode().value()).as(who + " POST /mcp/message").isEqualTo(404);
		}
	}
}
