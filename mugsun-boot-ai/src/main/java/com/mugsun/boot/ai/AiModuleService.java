package com.mugsun.boot.ai;

import com.mugsun.boot.common.module.AiModuleStatus;
import com.mugsun.boot.system.service.ParamService;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AI 模块开关：yml 与 sys_param 同时为开才启用；默认都开。
 */
@Service
public class AiModuleService implements AiModuleStatus {

	private final ParamService paramService;

	@Value("${mugsun.ai.enabled:true}")
	private boolean yamlEnabled;

	public AiModuleService(ParamService paramService) {
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
		if (!yamlEnabled) {
			return false;
		}
		String v = paramService.getValue(AiConstants.PARAM_MODULE_ENABLED);
		if (v == null || v.isBlank()) {
			return true;
		}
		return "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
	}

	public void requireEnabled() {
		if (!isEnabled()) {
			throw new ServiceException(AiConstants.MSG_DISABLED);
		}
	}
}
