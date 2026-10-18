package com.mugsun.boot.common.module;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 可选模块请求闸门装配：按路径前缀把请求挂到对应模块的 {@link ModuleGateInterceptor}。
 * <p>最高优先级、先于登录与租户守卫：模块关闭时匿名请求也是 404，不先暴露「需要登录」，与接口未注册时一致。
 * <p>前缀与各模块 Controller 的 {@code @RequestMapping} 一一对应，新增模块根路径要同步加到这里（server 模块的 {@code ModuleGateApiTest} 校验每个模块 Controller 都在闸门覆盖内）。
 * <p>拦截器同样作用于 {@code RouterFunctionMapping}，所以 Spring AI MCP Server 以 RouterFunction 注册的端点也受 AI 闸门控制（B87-2）。
 */
@Configuration
public class ModuleGateConfig implements WebMvcConfigurer {

	/** AI：管理端 + OpenAI 兼容开放接口（MCP Server 端点按配置另加，见 {@link #mcpPaths(Environment)}） */
	public static final String[] AI_PATHS = {"/system/ai/**", "/v1/**"};
	/** GIS：管理端 */
	public static final String[] GIS_PATHS = {"/system/gis/**"};
	/**
	 * GIS 闸门放行的路径：{@code /system/gis/status} 只报告模块状态，关闭时返回 {@code {enabled:false}}，
	 * 管理台各 GIS 页面据此展示空态，不弹错（B87-6）。仍需登录。
	 */
	public static final String[] GIS_UNGATED_PATHS = {"/system/gis/status"};
	/** 埋点：SDK 上报 + 管理端 */
	public static final String[] TRACK_PATHS = {"/track/**", "/system/track/**"};

	/** Spring AI MCP Server 端点配置项及其缺省值（spring-ai-autoconfigure-mcp-server-common） */
	static final String[][] MCP_ENDPOINT_PROPERTIES = {
		{"spring.ai.mcp.server.sse-endpoint", "/sse"},
		{"spring.ai.mcp.server.sse-message-endpoint", "/mcp/message"},
		{"spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp"},
	};

	private final AiModuleStatus aiModuleStatus;
	private final GisModuleStatus gisModuleStatus;
	private final TrackModuleStatus trackModuleStatus;
	private final Environment environment;

	public ModuleGateConfig(AiModuleStatus aiModuleStatus, GisModuleStatus gisModuleStatus,
							TrackModuleStatus trackModuleStatus, Environment environment) {
		this.aiModuleStatus = aiModuleStatus;
		this.gisModuleStatus = gisModuleStatus;
		this.trackModuleStatus = trackModuleStatus;
		this.environment = environment;
	}

	/** MCP Server 端点（SSE、消息、Streamable HTTP），按当前配置取值，未配置用缺省值；每个端点连同其子路径 */
	public static String[] mcpPaths(Environment environment) {
		Set<String> paths = new LinkedHashSet<>();
		for (String[] prop : MCP_ENDPOINT_PROPERTIES) {
			String value = environment.getProperty(prop[0], prop[1]);
			if (value == null || value.isBlank()) {
				value = prop[1];
			}
			String path = value.trim();
			if (!path.startsWith("/")) {
				path = "/" + path;
			}
			while (path.length() > 1 && path.endsWith("/")) {
				path = path.substring(0, path.length() - 1);
			}
			paths.add(path);
			paths.add(path + "/**");
		}
		return paths.toArray(String[]::new);
	}

	/** AI 闸门的全部路径：管理端、/v1 开放接口、MCP Server 端点 */
	public String[] aiPaths() {
		Set<String> paths = new LinkedHashSet<>(List.of(AI_PATHS));
		paths.addAll(List.of(mcpPaths(environment)));
		return paths.toArray(String[]::new);
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new ModuleGateInterceptor("ai", aiModuleStatus::enabled))
			.addPathPatterns(aiPaths())
			.order(Ordered.HIGHEST_PRECEDENCE);
		registry.addInterceptor(new ModuleGateInterceptor("gis", gisModuleStatus::enabled))
			.addPathPatterns(GIS_PATHS)
			.excludePathPatterns(GIS_UNGATED_PATHS)
			.order(Ordered.HIGHEST_PRECEDENCE);
		registry.addInterceptor(new ModuleGateInterceptor("track", trackModuleStatus::enabled))
			.addPathPatterns(TRACK_PATHS)
			.order(Ordered.HIGHEST_PRECEDENCE);
	}
}
