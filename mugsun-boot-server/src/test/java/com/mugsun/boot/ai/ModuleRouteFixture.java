package com.mugsun.boot.ai;

import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * {@code ModuleGateApiTest} 的夹具：一个「属于 ai 包」的 RouterFunction，用来证明模块接口覆盖守卫能抓到函数式路由。
 * <p>故意不是 Bean（没有任何 Spring 注解）：MugsunApplication 的组件扫描会扫到测试类路径，不能把它注册进集成测试上下文。
 */
public final class ModuleRouteFixture {

	private ModuleRouteFixture() {
	}

	/** 两条落在 AI 闸门内（其中一条经嵌套），一条故意漏在闸门外 */
	public static RouterFunction<ServerResponse> routes() {
		return RouterFunctions.route()
			.GET("/system/ai/fixture/ok", req -> ServerResponse.ok().build())
			.nest(RequestPredicates.path("/v1"), b -> b.POST("/fixture/chat", req -> ServerResponse.ok().build()))
			.GET("/ai-fixture/leak", req -> ServerResponse.ok().build())
			.build();
	}
}
