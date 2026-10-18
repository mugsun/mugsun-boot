package com.mugsun.boot.common.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.util.ServletRequestPathUtils;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleGateConfigTest {

	/** 暴露 protected 的 getInterceptors() */
	static class Registry extends InterceptorRegistry {
		List<MappedInterceptor> mapped() {
			return getInterceptors().stream().map(MappedInterceptor.class::cast).toList();
		}
	}

	private final AtomicBoolean ai = new AtomicBoolean(true);
	private final AtomicBoolean gis = new AtomicBoolean(true);
	private final AtomicBoolean track = new AtomicBoolean(true);

	private List<MappedInterceptor> interceptors() {
		return interceptors(new MockEnvironment());
	}

	private List<MappedInterceptor> interceptors(MockEnvironment environment) {
		AiModuleStatus aiStatus = new AiModuleStatus() {
			public boolean present() { return true; }
			public boolean enabled() { return ai.get(); }
		};
		GisModuleStatus gisStatus = new GisModuleStatus() {
			public boolean present() { return true; }
			public boolean enabled() { return gis.get(); }
		};
		TrackModuleStatus trackStatus = new TrackModuleStatus() {
			public boolean present() { return true; }
			public boolean enabled() { return track.get(); }
		};
		Registry registry = new Registry();
		new ModuleGateConfig(aiStatus, gisStatus, trackStatus, environment).addInterceptors(registry);
		return registry.mapped();
	}

	/** 依次跑命中该路径的闸门；全部放行返回 true */
	private boolean pass(List<MappedInterceptor> list, String method, String path) throws Exception {
		MockHttpServletRequest req = request(method, path);
		for (MappedInterceptor mi : list) {
			if (mi.matches(req) && !mi.getInterceptor().preHandle(req, new MockHttpServletResponse(), new Object())) {
				return false;
			}
		}
		return true;
	}

	/** 与 DispatcherServlet 一样先解析并缓存 RequestPath，MappedInterceptor 才能按路径匹配 */
	private static MockHttpServletRequest request(String method, String path) {
		MockHttpServletRequest req = new MockHttpServletRequest(method, path);
		ServletRequestPathUtils.parseAndCache(req);
		return req;
	}

	private String gateFor(List<MappedInterceptor> list, String path) {
		MockHttpServletRequest req = request("GET", path);
		return list.stream().filter(mi -> mi.matches(req))
			.map(mi -> ((ModuleGateInterceptor) mi.getInterceptor()).module())
			.findFirst().orElse(null);
	}

	@Test
	@DisplayName("各模块根路径挂到对应闸门，形似路径不误伤")
	void pathsMapToModule() {
		List<MappedInterceptor> list = interceptors();
		assertThat(gateFor(list, "/system/ai/model/page")).isEqualTo("ai");
		assertThat(gateFor(list, "/v1/models")).isEqualTo("ai");
		assertThat(gateFor(list, "/v1/chat/completions")).isEqualTo("ai");
		assertThat(gateFor(list, "/system/gis/status")).as("状态接口不过闸门").isNull();
		assertThat(gateFor(list, "/system/gis/spatial/status")).isEqualTo("gis");
		// MCP Server 缺省端点
		assertThat(gateFor(list, "/sse")).isEqualTo("ai");
		assertThat(gateFor(list, "/mcp/message")).isEqualTo("ai");
		assertThat(gateFor(list, "/mcp")).isEqualTo("ai");
		assertThat(gateFor(list, "/system/gis/scene/submit")).isEqualTo("gis");
		assertThat(gateFor(list, "/track/collect")).isEqualTo("track");
		assertThat(gateFor(list, "/system/track/app/page")).isEqualTo("track");
		for (String other : new String[] {"/system/aix", "/system/user/page", "/v10/models", "/v2/x",
			"/system/gist", "/trackx/collect", "/auth/info", "/open/v1/models", "/ssex", "/mcpx"}) {
			assertThat(gateFor(list, other)).as(other).isNull();
		}
	}

	@Test
	@DisplayName("仅系统参数关闭（enabled()=false）时抛 NoResourceFoundException → 404，与未注册同链路")
	void disabledThrowsNotFound() throws Exception {
		List<MappedInterceptor> list = interceptors();
		ai.set(false);
		assertThatThrownBy(() -> pass(list, "GET", "/v1/models"))
			.isInstanceOf(NoResourceFoundException.class)
			.hasMessageContaining("/v1/models");
		assertThatThrownBy(() -> pass(list, "POST", "/system/ai/model/submit"))
			.isInstanceOf(NoResourceFoundException.class);
		assertThatThrownBy(() -> pass(list, "GET", "/sse")).isInstanceOf(NoResourceFoundException.class);
		assertThatThrownBy(() -> pass(list, "POST", "/mcp/message")).isInstanceOf(NoResourceFoundException.class);
		assertThatThrownBy(() -> pass(list, "POST", "/mcp")).isInstanceOf(NoResourceFoundException.class);
		// 其他模块与核心接口不受影响
		assertThat(pass(list, "GET", "/system/gis/scene/page")).isTrue();
		assertThat(pass(list, "GET", "/system/user/page")).isTrue();

		gis.set(false);
		assertThatThrownBy(() -> pass(list, "GET", "/system/gis/scene/page"))
			.isInstanceOf(NoResourceFoundException.class);
		assertThat(pass(list, "GET", "/system/gis/status")).as("状态接口照常返回 {enabled:false}").isTrue();
		track.set(false);
		assertThatThrownBy(() -> pass(list, "POST", "/track/collect"))
			.isInstanceOf(NoResourceFoundException.class);
	}

	@Test
	@DisplayName("MCP 端点按 spring.ai.mcp.server.* 实际配置取路径，自定义后旧缺省路径不再挂闸门")
	void mcpPathsFollowConfiguration() throws Exception {
		MockEnvironment env = new MockEnvironment()
			.withProperty("spring.ai.mcp.server.sse-endpoint", "/ai-sse/")
			.withProperty("spring.ai.mcp.server.sse-message-endpoint", "ai-mcp/msg")
			.withProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/ai-mcp-stream");
		List<MappedInterceptor> list = interceptors(env);
		assertThat(gateFor(list, "/ai-sse")).isEqualTo("ai");
		assertThat(gateFor(list, "/ai-mcp/msg")).isEqualTo("ai");
		assertThat(gateFor(list, "/ai-mcp-stream")).isEqualTo("ai");
		assertThat(gateFor(list, "/sse")).isNull();
		ai.set(false);
		assertThatThrownBy(() -> pass(list, "POST", "/ai-mcp/msg")).isInstanceOf(NoResourceFoundException.class);
	}

	@Test
	@DisplayName("MCP 端点配置为空白时退回缺省值")
	void blankMcpPathsFallBackToDefaults() {
		MockEnvironment env = new MockEnvironment().withProperty("spring.ai.mcp.server.sse-endpoint", " ");
		assertThat(ModuleGateConfig.mcpPaths(env)).contains("/sse", "/sse/**", "/mcp/message", "/mcp");
	}

	@Test
	@DisplayName("每个请求实时判定：参数改回开启后立即放行，无需重启")
	void evaluatedPerRequest() throws Exception {
		List<MappedInterceptor> list = interceptors();
		assertThat(pass(list, "GET", "/system/ai/model/page")).isTrue();
		ai.set(false);
		assertThatThrownBy(() -> pass(list, "GET", "/system/ai/model/page"))
			.isInstanceOf(NoResourceFoundException.class);
		ai.set(true);
		assertThat(pass(list, "GET", "/system/ai/model/page")).isTrue();
	}

	@Test
	@DisplayName("带 context-path 时按应用内路径报 404")
	void contextPathStripped() {
		ModuleGateInterceptor gate = new ModuleGateInterceptor("ai", () -> false);
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/models");
		req.setContextPath("/api");
		assertThatThrownBy(() -> gate.preHandle(req, new MockHttpServletResponse(), new Object()))
			.isInstanceOf(NoResourceFoundException.class)
			.hasMessageContaining("/v1/models")
			.hasMessageNotContaining("/api/v1");
	}
}
