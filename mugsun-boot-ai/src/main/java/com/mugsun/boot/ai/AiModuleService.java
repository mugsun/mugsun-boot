package com.mugsun.boot.ai;

import com.mugsun.boot.common.module.AiModuleStatus;
import com.mugsun.boot.common.module.ModuleSwitch;
import com.mugsun.boot.common.module.ModuleSwitchRuntime;
import com.mugsun.boot.system.service.ParamService;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/**
 * AI 模块开关：yml 与 sys_param 同时为开才启用；默认都开。
 */
@Service
public class AiModuleService implements AiModuleStatus {

	private final ParamService paramService;

	/** yml / 环境变量开关原值；{@code null} 表示属性不存在。按 {@link ModuleSwitch#parseStrict} 解析，与装配条件同一规则 */
	private final String yamlRaw;

	public AiModuleService(ParamService paramService, Environment environment) {
		this.paramService = paramService;
		this.yamlRaw = environment.getProperty(YAML_KEY);
	}

	static final String YAML_KEY = "mugsun.ai.enabled";

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
		return ModuleSwitchRuntime.shared().enabled(AiConstants.PARAM_MODULE_ENABLED,
			paramService.getValue(AiConstants.PARAM_MODULE_ENABLED));
	}

	public void requireEnabled() {
		if (!isEnabled()) {
			throw new ServiceException(AiConstants.MSG_DISABLED);
		}
	}
}
