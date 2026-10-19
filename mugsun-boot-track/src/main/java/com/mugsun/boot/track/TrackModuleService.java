package com.mugsun.boot.track;

import com.mugsun.boot.common.module.ModuleSwitch;
import com.mugsun.boot.common.module.TrackModuleStatus;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * 埋点模块总开关：yml {@code mugsun.track.enabled}；默认开。
 * 实现 {@link TrackModuleStatus} 供核心菜单过滤，避免核心硬依赖 track 包。
 */
@Service
public class TrackModuleService implements TrackModuleStatus {

	/** yml / 环境变量开关原值；{@code null} 表示属性不存在。按 {@link ModuleSwitch#parseStrict} 解析，与装配条件同一规则 */
	private final String yamlRaw;

	public TrackModuleService(Environment environment) {
		this.yamlRaw = environment.getProperty("mugsun.track.enabled");
	}

	@Override
	public boolean present() {
		return true;
	}

	@Override
	public boolean enabled() {
		return ModuleSwitch.parseStrict(yamlRaw).enabled();
	}
}
