package com.mugsun.boot.gis;

import com.mugsun.boot.common.module.GisModuleStatus;
import com.mugsun.boot.common.module.ModuleSwitch;
import com.mugsun.boot.common.module.ModuleSwitchRuntime;
import com.mugsun.boot.system.service.ParamService;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * GIS 模块开关：yml 与 sys_param 同时为开才启用；默认都开。
 * 实现 {@link GisModuleStatus} 供核心菜单/登录态查询，避免核心硬依赖本包以外类型。
 */
@Service
public class GisModuleService implements GisModuleStatus {

	private final ParamService paramService;

	/** yml / 环境变量开关原值；{@code null} 表示属性不存在。按 {@link ModuleSwitch#parseStrict} 解析，与装配条件同一规则 */
	private final String yamlRaw;

	public GisModuleService(ParamService paramService, Environment environment) {
		this.paramService = paramService;
		this.yamlRaw = environment.getProperty(YAML_KEY);
	}

	static final String YAML_KEY = "mugsun.gis.enabled";

	@Override
	public boolean present() {
		return true;
	}

	@Override
	public boolean enabled() {
		return isEnabled();
	}

	public boolean isEnabled() {
		if (!ModuleSwitch.parseStrict(yamlRaw).enabled()) {
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
