package com.mugsun.boot.common.setting;

import com.mugsun.boot.common.module.ModuleSwitch;

/**
 * yml / 环境变量里的枚举型配置取值非法（见 {@link StrictEnumSetting}），启动失败。
 * {@link #getMessage()} 与 {@link #getDisplayValue()} 里的原值都已经过 {@link ModuleSwitch#quote} 截断并转义控制字符。
 */
public class InvalidSettingException extends IllegalStateException {

	private final String property;
	private final String displayValue;
	private final String reason;

	public InvalidSettingException(String property, String raw, String reason) {
		super("配置项 " + property + "（环境变量 " + ModuleSwitch.envName(property) + "）的值 " + ModuleSwitch.quote(raw)
			+ " 不合法：" + reason);
		this.property = property;
		this.displayValue = ModuleSwitch.quote(raw);
		this.reason = reason;
	}

	public String getProperty() {
		return property;
	}

	public String getEnvName() {
		return ModuleSwitch.envName(property);
	}

	/** 已转义、可直接进日志的原值（带单引号） */
	public String getDisplayValue() {
		return displayValue;
	}

	/** 不合法的原因与改法（不含原值以外的用户输入；原值部分已转义） */
	public String getReason() {
		return reason;
	}
}
