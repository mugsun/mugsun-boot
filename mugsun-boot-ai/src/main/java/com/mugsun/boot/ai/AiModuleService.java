package com.mugsun.boot.ai;

import com.mugsun.boot.common.module.AiModuleStatus;
import com.mugsun.boot.common.module.ModuleSwitch;
import com.mugsun.boot.common.module.ModuleSwitchRuntime;
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

	/** 按字符串读，交给 {@link ModuleSwitch} 解析；不用 boolean 注入（空值会转换失败、yes/on 会被 Spring 当成真） */
	@Value("${mugsun.ai.enabled:}")
	private String yamlRaw;

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
		if (!ModuleSwitch.parse(yamlRaw).enabled()) {
			return false;
		}
		return ModuleSwitchRuntime.shared().enabled(AiConstants.PARAM_MODULE_ENABLED,
			paramService.getValue(AiConstants.PARAM_MODULE_ENABLED));
	}

	public void requireEnabled() {
		if (!isEnabled()) {
			throw new ServiceException(AiConstants.MSG_DISABLED);
		}
	}
}
