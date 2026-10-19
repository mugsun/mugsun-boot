package com.mugsun.boot;

import com.mugsun.boot.ai.ModuleRouteFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ModuleRouteGuard} 自检：不起容器，直接喂 RouterFunction。 */
class ModuleRouteGuardTest {

	@Test
	@DisplayName("守卫自检：ai 包里的 RouterFunction 漏在闸门外会被抓到（含嵌套路由的完整路径），MCP 路由一律算违规")
	void routerFunctionGuardCatchesUngatedModuleRoute() {
		List<ModuleRouteGuard.FnRoute> routes = ModuleRouteGuard.flatten(ModuleRouteFixture.routes());
		assertThat(routes).extracting(ModuleRouteGuard.FnRoute::path)
			.containsExactly("/system/ai/fixture/ok", "/v1/fixture/chat", "/ai-fixture/leak");
		assertThat(routes).allMatch(r -> r.owner().startsWith("com.mugsun.boot.ai"));
		assertThat(ModuleRouteGuard.violations(routes)).singleElement().asString().contains("/ai-fixture/leak");

		RouterFunction<?> mcp = RouterFunctions.route()
			.GET("/sse", req -> ServerResponse.ok().build())
			.POST("/mcp/message", req -> ServerResponse.ok().build())
			.build();
		assertThat(ModuleRouteGuard.violations(ModuleRouteGuard.flatten(mcp))).hasSize(2);
	}

	@Test
	@DisplayName("gis/track 包同理；核心包与未知归属的路由不归模块守卫管")
	void otherModulesAndCore() {
		List<String> v = ModuleRouteGuard.violations(List.of(
			new ModuleRouteGuard.FnRoute("com.mugsun.boot.gis.x", "/system/gis/a"),
			new ModuleRouteGuard.FnRoute("com.mugsun.boot.gis.x", "/gis/leak"),
			new ModuleRouteGuard.FnRoute("com.mugsun.boot.track", "/track/collect"),
			new ModuleRouteGuard.FnRoute("com.mugsun.boot.track", null),
			new ModuleRouteGuard.FnRoute("com.mugsun.boot.system", "/core/ping"),
			new ModuleRouteGuard.FnRoute("org.springframework.ai.mcp", "/anything")));
		assertThat(v).hasSize(3)
			.anyMatch(s -> s.contains("/gis/leak"))
			.anyMatch(s -> s.contains("解析不出路径"))
			.anyMatch(s -> s.contains("org.springframework.ai.mcp"));
	}
}
