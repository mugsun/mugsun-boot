package com.mugsun.boot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import com.mugsun.boot.ai.controller.AiChatController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全回归（B1）：AI jar 在类路径上、但 {@code mugsun.ai.enabled=false} 时，AI 接口一律 404，菜单与菜单管理树也不出现 AI。
 * <p>属性与默认上下文不同，且 warm-flow 不允许同 JVM 多上下文，故在独立 surefire fork（ai-disabled-it）里跑。
 */
class AiDisabledApiTest extends AbstractIntegrationTest {

	static {
		PROPERTY_OVERRIDES.put("mugsun.ai.enabled", () -> "false");
	}

	@Autowired
	private ApplicationContext context;

	private static String adminToken;

	private String token() {
		if (adminToken == null) {
			adminToken = loginAdmin();
		}
		return adminToken;
	}

	@Test
	@DisplayName("AI Controller 不在容器里，也没有任何 /system/ai 映射")
	void noAiHandlers() {
		assertThat(context.getBeanNamesForType(AiChatController.class)).isEmpty();
		RequestMappingHandlerMapping mapping =
			context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
		List<String> patterns = new ArrayList<>();
		mapping.getHandlerMethods().keySet().forEach(info -> patterns.addAll(info.getPatternValues()));
		assertThat(patterns).noneMatch(p -> p.startsWith("/system/ai"));
	}

	@Test
	@DisplayName("所有 HandlerMapping 里没有任何一个 handler 的 beanType 属于 ai 包")
	void noHandlerBeanTypeInAiPackage() {
		List<String> aiHandlers = new ArrayList<>();
		context.getBeansOfType(RequestMappingHandlerMapping.class).forEach((name, mapping) -> {
			for (HandlerMethod hm : mapping.getHandlerMethods().values()) {
				String pkg = hm.getBeanType().getPackageName();
				if (pkg.equals("com.mugsun.boot.ai") || pkg.startsWith("com.mugsun.boot.ai.")) {
					aiHandlers.add(name + " -> " + hm.getBeanType().getName() + "#" + hm.getMethod().getName());
				}
			}
		});
		assertThat(aiHandlers).isEmpty();
	}

	@Test
	@DisplayName("函数式路由（RouterFunction，如 Spring AI MCP Server 的 SSE 端点）里也没有 AI 路由")
	void noAiRouterFunctionRoutes() {
		List<String> routes = new ArrayList<>();
		context.getBeansOfType(RouterFunctionMapping.class).forEach((name, mapping) -> {
			RouterFunction<?> fn = mapping.getRouterFunction();
			if (fn != null) {
				fn.accept(new RouteCollector(name, routes));
			}
		});
		// RouterFunction Bean 本身（即便未被 mapping 收录）也不应来自 AI
		context.getBeansOfType(RouterFunction.class).forEach((name, fn) -> fn.accept(new RouteCollector(name, routes)));
		assertThat(routes).noneMatch(AiDisabledApiTest::isAiRoute);
	}

	private static boolean isAiRoute(String route) {
		return route.contains("com.mugsun.boot.ai.") || route.contains("org.springframework.ai.")
			|| route.contains("/system/ai") || route.contains("/v1/") || route.contains("/mcp") || route.contains("/sse");
	}

	/** 把 RouterFunction 树拍平成「谓词 → handler 类型」字符串 */
	private record RouteCollector(String source, List<String> out) implements RouterFunctions.Visitor {
		@Override
		public void startNested(RequestPredicate predicate) {
			out.add(source + " nested " + predicate);
		}

		@Override
		public void endNested(RequestPredicate predicate) {
		}

		@Override
		public void route(RequestPredicate predicate, HandlerFunction<?> handlerFunction) {
			out.add(source + " " + predicate + " -> " + handlerFunction.getClass().getName());
		}

		@Override
		public void resources(Function<ServerRequest, Optional<Resource>> lookupFunction) {
			out.add(source + " resources " + lookupFunction.getClass().getName());
		}

		@Override
		public void attributes(Map<String, Object> attributes) {
		}

		@Override
		public void unknown(RouterFunction<?> routerFunction) {
			out.add(source + " unknown " + routerFunction.getClass().getName() + " " + routerFunction);
		}
	}

	@Test
	@DisplayName("OpenAI 兼容开放接口 /v1/models 返回 404（匿名与管理员）")
	void openApiModelsReturn404() {
		assertThat(get("/v1/models", null).getStatusCode().value()).isEqualTo(404);
		assertThat(get("/v1/models", token()).getStatusCode().value()).isEqualTo(404);
		assertThat(post("/v1/chat/completions", java.util.Map.of(), null).getStatusCode().value()).isEqualTo(404);
	}

	@Test
	@DisplayName("管理员访问 AI 接口返回 404")
	void aiEndpointsReturn404() {
		String token = token();
		assertThat(get("/system/ai/model/page", token).getStatusCode().value()).isEqualTo(404);
		assertThat(get("/system/ai/chat/replay", token).getStatusCode().value()).isEqualTo(404);
		assertThat(post("/system/ai/model/submit", java.util.Map.of(), token).getStatusCode().value()).isEqualTo(404);
	}

	@Test
	@DisplayName("用户菜单与菜单管理树都不含 /ai")
	void menusHideAi() {
		String token = token();
		JsonNode routes = readBody(get("/v3/system/menus", token)).path("data");
		assertThat(collectPaths(routes, "")).noneMatch(p -> p.equals("/ai") || p.startsWith("/ai/"));
		JsonNode tree = readBody(get("/system/menu/tree", token)).path("data");
		assertThat(collectPaths(tree, null)).noneMatch(p -> p.equals("/ai") || p.startsWith("/ai/"));
	}

	/** routes 的子 path 是相对路径（parentPrefix 非空时拼接）；菜单管理树是完整 path（parentPrefix 为 null） */
	private static List<String> collectPaths(JsonNode nodes, String parentPrefix) {
		List<String> out = new ArrayList<>();
		if (nodes == null || !nodes.isArray()) {
			return out;
		}
		for (JsonNode n : nodes) {
			String p = n.path("path").asText("");
			String full = parentPrefix == null || p.startsWith("/") ? p : parentPrefix + "/" + p;
			if (!full.isEmpty()) {
				out.add(full);
			}
			out.addAll(collectPaths(n.path("children"), parentPrefix == null ? null : full));
		}
		return out;
	}

	@Autowired
	private org.springframework.core.env.Environment environment;

	@Test
	@DisplayName("B89-0 守卫：内置 MCP Server 已移除；即使打开 MUGSUN_AI_MCP_SERVER / spring.ai.mcp.server.enabled，/sse、/mcp、/mcp/message 仍是 404（匿名与管理员）")
	void mcpEndpointsAlways404WhenAiOff() {
		assertThat(System.getenv("MUGSUN_AI_MCP_SERVER")).as("前提：本 fork 打开了 MCP Server 开关").isEqualTo("true");
		assertThat(environment.getProperty("spring.ai.mcp.server.enabled")).isEqualTo("true");
		assertThat(environment.getProperty("mugsun.ai.enabled")).isEqualTo("false");
		String token = token();
		for (String t : new String[] {null, token}) {
			String who = t == null ? "匿名" : "管理员";
			assertThat(get("/sse", t).getStatusCode().value()).as(who + " GET /sse").isEqualTo(404);
			assertThat(get("/mcp", t).getStatusCode().value()).as(who + " GET /mcp").isEqualTo(404);
			assertThat(post("/mcp", java.util.Map.of(), t).getStatusCode().value()).as(who + " POST /mcp").isEqualTo(404);
			assertThat(post("/mcp/message", java.util.Map.of(), t).getStatusCode().value()).as(who + " POST /mcp/message").isEqualTo(404);
		}
	}
}
