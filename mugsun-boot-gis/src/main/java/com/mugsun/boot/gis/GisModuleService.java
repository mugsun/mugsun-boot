package com.mugsun.boot.gis;

import com.mugsun.boot.common.module.GisModuleStatus;
import com.mugsun.boot.common.module.ModuleSwitch;
import com.mugsun.boot.common.module.ModuleSwitchRuntime;
import com.mugsun.boot.system.service.ParamService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * GIS 模块开关：yml 与 sys_param 同时为开才启用；默认都开。
 * 实现 {@link GisModuleStatus} 供核心菜单/登录态查询，避免核心硬依赖本包以外类型。
 */
@Service
public class GisModuleService implements GisModuleStatus {

	private final ParamService paramService;

	/** 按字符串读，交给 {@link ModuleSwitch} 解析；不用 boolean 注入（空值会转换失败、yes/on 会被 Spring 当成真） */
	@Value("${mugsun.gis.enabled:}")
	private String yamlRaw;

	public GisModuleService(ParamService paramService) {
		this.paramService = paramService;
	}

	@Override
	public boolean present() {
		return true;
	}

	@Override
	public boolean enabled() {
		return isEnabled();
	}

	public boolean isEnabled() {
		if (!ModuleSwitch.parse(yamlRaw).enabled()) {
			return false;
		}
		return ModuleSwitchRuntime.shared().enabled(GisConstants.PARAM_MODULE_ENABLED,
			paramService.getValue(GisConstants.PARAM_MODULE_ENABLED));
	}

	/**
	 * 地形服务地址（quantized-mesh）。没配就返回空串，前端据此把地形开关置灰，
	 * 而不是给一个点了没反应的开关。
	 */
	@Override
	public String terrainUrl() {
		String v = paramService.getValue(GisConstants.PARAM_TERRAIN_URL);
		if (v == null) {
			return "";
		}
		String url = v.trim();
		return url.startsWith("http://") || url.startsWith("https://") ? url : "";
	}

	public void requireEnabled() {
		if (!isEnabled()) {
			throw new com.mugsun.core.tool.exception.ServiceException(GisConstants.MSG_DISABLED);
		}
	}
}
