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
	@DisplayName("B89-0 守卫：内置 MCP Server 已移除；即使打开 MUGSUN_AI_MCP_SERVER / spring.ai.mcp.server.enabled，/sse、/mcp、/mcp/message 仍是 404（匿名与管理员）")
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

	@Test
	@DisplayName("MCP Server 管理页下线：/system/ai/mcp/server/list、/server/toggle 未注册（管理员 404），MCP 工具箱其它接口照常；ai:mcp:server 按钮与授权已逻辑删除")
	void mcpServerAdminPagesOffline() throws Exception {
		RequestMappingHandlerMapping mapping =
			context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
		List<String> patterns = new ArrayList<>();
		mapping.getHandlerMethods().keySet().forEach(info -> patterns.addAll(info.getPatternValues()));
		assertThat(patterns).contains("/system/ai/mcp/page")
			.noneMatch(p -> p.startsWith("/system/ai/mcp/server"));

		String token = loginAdmin();
		assertThat(get("/system/ai/mcp/server/list", token).getStatusCode().value()).as("GET /server/list").isEqualTo(404);
		assertThat(post("/system/ai/mcp/server/toggle", java.util.Map.of("id", "1", "status", 1), token).getStatusCode().value())
			.as("POST /server/toggle").isEqualTo(404);
		ResponseEntity<String> page = get("/system/ai/mcp/page", token);
		assertThat(page.getStatusCode().value()).as("MCP 工具箱列表照常：" + page.getBody()).isEqualTo(200);
		assertThat(readBody(page).path("code").asInt()).isEqualTo(200);

		try (java.sql.Connection c = java.sql.DriverManager.getConnection(primaryJdbcUrl(), primaryJdbcUsername(), primaryJdbcPassword());
			 java.sql.Statement st = c.createStatement()) {
			assertThat(count(st, "SELECT count(*) FROM sys_menu WHERE permission = 'ai:mcp:server' AND is_deleted = 0"))
				.as("V4：对外暴露按钮已逻辑删除").isZero();
			assertThat(count(st, "SELECT count(*) FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id "
				+ "WHERE m.permission = 'ai:mcp:server' AND rm.is_deleted = 0")).as("V4：角色上的该授权已撤销").isZero();
			assertThat(count(st, "SELECT count(*) FROM sys_menu WHERE permission IN ('ai:mcp:list','ai:mcp:save') AND is_deleted = 0"))
				.as("MCP 工具箱页面与其它按钮保留").isEqualTo(2);
			assertThat(count(st, "SELECT count(*) FROM information_schema.tables WHERE table_name = 'ai_mcp_tool'"))
				.as("ai_mcp_tool 表保留").isEqualTo(1);
		}
		JsonNode tree = readBody(get("/system/menu/tree", token)).path("data");
		assertThat(tree.toString()).as("菜单管理树不含 ai:mcp:server").doesNotContain("ai:mcp:server");
	}

	private static long count(java.sql.Statement st, String sql) throws java.sql.SQLException {
		try (java.sql.ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
	}
}
