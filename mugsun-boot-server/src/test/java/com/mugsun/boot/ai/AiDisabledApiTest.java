package com.mugsun.boot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import com.mugsun.boot.ai.controller.AiChatController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;

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
}
