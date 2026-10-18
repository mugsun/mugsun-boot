package com.mugsun.boot.common.module;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模块若以 RouterFunction 注册路由（不走 {@code @RequestMapping}），落在闸门前缀内同样受控。
 * 这里用真实的 Spring MVC 装配（{@code @EnableWebMvc} → RouterFunctionMapping）验证。
 * <p>B89-0：内置 MCP Server 暂时下线（加鉴权后恢复），{@code spring.ai.mcp.server.*} 不再影响闸门（本例故意设置了该配置，作反向守卫）。
 */
@SpringJUnitWebConfig(ModuleGateRouterFunctionTest.WebConfig.class)
@TestPropertySource(properties = {"spring.ai.mcp.server.enabled=true", "spring.ai.mcp.server.sse-endpoint=/custom-sse"})
class ModuleGateRouterFunctionTest {

	static final AtomicBoolean AI = new AtomicBoolean(true);

	/**
	 * 故意不加 {@code @Configuration}：MugsunApplication 的 {@code @ComponentScan} 覆盖了 com.mugsun.boot 且没有 TypeExcludeFilter，
	 * 加了就会被集成测试上下文扫进去，{@code @EnableWebMvc} 会关掉 Boot 的 MVC 自动装配（静态资源 404 变成 500）。
	 * 只经 {@code @SpringJUnitWebConfig} 显式注册。
	 */
	@EnableWebMvc
	@Import(ModuleGateConfig.class)
	static class WebConfig {

		@Bean
		AiModuleStatus aiModuleStatus() {
			return new AiModuleStatus() {
				public boolean present() { return true; }
				public boolean enabled() { return AI.get(); }
			};
		}

		@Bean
		GisModuleStatus gisModuleStatus() {
			return new GisModuleStatus() {
				public boolean present() { return true; }
				public boolean enabled() { return true; }
			};
		}

		@Bean
		TrackModuleStatus trackModuleStatus() {
			return new TrackModuleStatus() {
				public boolean present() { return true; }
				public boolean enabled() { return true; }
			};
		}

		/** AI 前缀内的函数式路由（含嵌套），外加一个不属于 AI 的路由作对照 */
		@Bean
		RouterFunction<ServerResponse> moduleRoutes() {
			return RouterFunctions.route()
				.GET("/system/ai/fn/ping", req -> ServerResponse.ok().body("ai"))
				.nest(org.springframework.web.servlet.function.RequestPredicates.path("/v1"), b -> b
					.POST("/fn/chat", req -> ServerResponse.ok().body("v1")))
				.GET("/core/ping", req -> ServerResponse.ok().body("pong"))
				.build();
		}
	}

	@Autowired
	private WebApplicationContext context;

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		AI.set(true);
		mvc = MockMvcBuilders.webAppContextSetup(context).build();
	}

	@Test
	@DisplayName("AI 开启：AI 前缀内的 RouterFunction 路由正常响应")
	void enabledPassesThrough() throws Exception {
		mvc.perform(get("/system/ai/fn/ping")).andExpect(status().isOk());
		mvc.perform(post("/v1/fn/chat")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("AI 关闭：AI 前缀内的 RouterFunction 路由（含嵌套）一律 404，其他路由不受影响")
	void disabledReturns404() throws Exception {
		AI.set(false);
		mvc.perform(get("/system/ai/fn/ping")).andExpect(status().isNotFound());
		mvc.perform(post("/v1/fn/chat")).andExpect(status().isNotFound());
		mvc.perform(get("/core/ping")).andExpect(status().isOk());
	}

	@Test
	@DisplayName("B89-0 反向守卫：即使配置了 spring.ai.mcp.server.*，也没有任何 MCP 端点（AI 开、关都是 404）")
	void noMcpEndpoints() throws Exception {
		for (boolean on : new boolean[] {true, false}) {
			AI.set(on);
			mvc.perform(get("/custom-sse")).andExpect(status().isNotFound());
			mvc.perform(get("/sse")).andExpect(status().isNotFound());
			mvc.perform(post("/mcp/message")).andExpect(status().isNotFound());
			mvc.perform(post("/mcp")).andExpect(status().isNotFound());
		}
	}
}
