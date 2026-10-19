package com.mugsun.boot.common.module;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
		new ModuleGateConfig(aiStatus, gisStatus, trackStatus).addInterceptors(registry);
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
	@DisplayName("B89-0 反向守卫：内置 MCP Server 暂时下线（加鉴权后恢复），AI 闸门只管管理端与 /v1，不再按 spring.ai.mcp.server.* 挂 MCP 端点")
	void noMcpEndpointsInAiGate() throws NoSuchMethodException {
		List<MappedInterceptor> list = interceptors();
		MappedInterceptor aiGate = list.stream()
			.filter(mi -> "ai".equals(((ModuleGateInterceptor) mi.getInterceptor()).module()))
			.findFirst().orElseThrow();
		assertThat(aiGate.getPathPatterns()).containsExactlyInAnyOrder("/system/ai/**", "/v1/**");
		for (String mcp : new String[] {"/sse", "/sse/x", "/mcp", "/mcp/message", "/custom-sse"}) {
			assertThat(gateFor(list, mcp)).as(mcp + " 不归任何模块闸门（没有处理器，本身就是 404）").isNull();
		}
		// 构造参数里不再有 Environment：闸门不读任何 spring.ai.* 配置
		assertThat(ModuleGateConfig.class.getConstructors()).singleElement()
			.satisfies(c -> assertThat(c.getParameterTypes()).doesNotContain(org.springframework.core.env.Environment.class));
		assertThat(java.util.Arrays.stream(ModuleGateConfig.class.getDeclaredFields()).map(java.lang.reflect.Field::getName))
			.noneMatch(n -> n.toUpperCase().contains("MCP"));
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
