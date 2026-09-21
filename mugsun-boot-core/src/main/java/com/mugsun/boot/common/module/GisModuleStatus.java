package com.mugsun.boot.common.module;

/**
 * GIS 可选模块状态（核心只依赖本接口，不依赖 gis 包）。
 * <p>无 gis 模块 jar 时由 {@link OptionalModuleConfiguration} 提供关闭默认实现；
 * 有 jar 时由 gis 模块注册真实实现。
 */
public interface GisModuleStatus {

	/** classpath 是否带有 GIS 模块（与运行时开关无关） */
	boolean present();

	/** 模块是否对用户可见/可用（yml ∩ sys_param） */
	boolean enabled();

	/** 地形服务 URL；未配置返回空串 */
	default String terrainUrl() {
		return "";
	}
}
