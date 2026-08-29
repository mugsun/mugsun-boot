package com.mugsun.boot.gis;

import com.mugsun.boot.system.service.ParamService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * GIS 模块开关：yml 与 sys_param 同时为开才启用；默认都开。
 */
@Service
public class GisModuleService {

	private final ParamService paramService;

	@Value("${mugsun.gis.enabled:true}")
	private boolean yamlEnabled;

	public GisModuleService(ParamService paramService) {
		this.paramService = paramService;
	}

	public boolean isEnabled() {
		if (!yamlEnabled) {
			return false;
		}
		String v = paramService.getValue(GisConstants.PARAM_MODULE_ENABLED);
		if (v == null || v.isBlank()) {
			return true;
		}
		return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
	}

	/**
	 * 地形服务地址（quantized-mesh）。没配就返回空串，前端据此把地形开关置灰，
	 * 而不是给一个点了没反应的开关。
	 */
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
