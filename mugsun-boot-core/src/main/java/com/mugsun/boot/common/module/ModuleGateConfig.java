package com.mugsun.boot.common.module;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 可选模块请求闸门装配：按路径前缀把请求挂到对应模块的 {@link ModuleGateInterceptor}。
 * <p>最高优先级、先于登录与租户守卫：模块关闭时匿名请求也是 404，不先暴露「需要登录」，与接口未注册时一致。
 * <p>前缀与各模块 Controller 的 {@code @RequestMapping} 一一对应，新增模块根路径要同步加到这里（server 模块的 {@code ModuleGateApiTest} 校验每个模块 Controller 都在闸门覆盖内）。
 */
@Configuration
public class ModuleGateConfig implements WebMvcConfigurer {

	/** AI：管理端 + OpenAI 兼容开放接口 */
	public static final String[] AI_PATHS = {"/system/ai/**", "/v1/**"};
	/** GIS：管理端 */
	public static final String[] GIS_PATHS = {"/system/gis/**"};
	/** 埋点：SDK 上报 + 管理端 */
	public static final String[] TRACK_PATHS = {"/track/**", "/system/track/**"};

	private final AiModuleStatus aiModuleStatus;
	private final GisModuleStatus gisModuleStatus;
	private final TrackModuleStatus trackModuleStatus;

	public ModuleGateConfig(AiModuleStatus aiModuleStatus, GisModuleStatus gisModuleStatus,
							TrackModuleStatus trackModuleStatus) {
		this.aiModuleStatus = aiModuleStatus;
		this.gisModuleStatus = gisModuleStatus;
		this.trackModuleStatus = trackModuleStatus;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new ModuleGateInterceptor("ai", aiModuleStatus::enabled))
			.addPathPatterns(AI_PATHS)
			.order(Ordered.HIGHEST_PRECEDENCE);
		registry.addInterceptor(new ModuleGateInterceptor("gis", gisModuleStatus::enabled))
			.addPathPatterns(GIS_PATHS)
			.order(Ordered.HIGHEST_PRECEDENCE);
		registry.addInterceptor(new ModuleGateInterceptor("track", trackModuleStatus::enabled))
			.addPathPatterns(TRACK_PATHS)
			.order(Ordered.HIGHEST_PRECEDENCE);
	}
}
