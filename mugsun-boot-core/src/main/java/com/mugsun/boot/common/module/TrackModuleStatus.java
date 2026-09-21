package com.mugsun.boot.common.module;

/**
 * 埋点可选模块状态（核心只依赖本接口，不依赖 track 包）。
 * <p>无 track 模块 jar 时由 {@link OptionalModuleConfiguration} 提供关闭默认实现；
 * 有 jar 时由 track 模块注册真实实现。
 */
public interface TrackModuleStatus {

	/** classpath 是否带有埋点模块 */
	boolean present();

	/** 模块是否启用（yml 总开关） */
	boolean enabled();
}
