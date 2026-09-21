package com.mugsun.boot.track;

import com.mugsun.boot.common.module.TrackModuleStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 埋点模块总开关：yml {@code mugsun.track.enabled}；默认开。
 * 实现 {@link TrackModuleStatus} 供核心菜单过滤，避免核心硬依赖 track 包。
 */
@Service
public class TrackModuleService implements TrackModuleStatus {

	@Value("${mugsun.track.enabled:true}")
	private boolean yamlEnabled;

	@Override
	public boolean present() {
		return true;
	}

	@Override
	public boolean enabled() {
		return yamlEnabled;
	}
}
