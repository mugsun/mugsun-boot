package com.mugsun.boot.common.module;

/**
 * yml / 环境变量层模块开关取值非法（见 {@link ModuleSwitch#parseStrict}）。由 {@link ModuleSwitchCondition} 在装配阶段抛出，
 * {@link ModuleSwitchFailureAnalyzer} 把它整理成 Spring Boot 启动失败时的 Description / Action 说明。
 * <p>{@link #getMessage()} 与 {@link #getDisplayValue()} 里的原值都已经过 {@link ModuleSwitch#quote} 截断并转义控制字符。
 */
public class InvalidModuleSwitchException extends IllegalStateException {

	private final String property;
	private final String displayValue;

	public InvalidModuleSwitchException(String property, String raw) {
		super(ModuleSwitch.strictError(property, raw));
		this.property = property;
		this.displayValue = ModuleSwitch.quote(raw);
	}

	/** 属性名，如 {@code mugsun.ai.enabled} */
	public String getProperty() {
		return property;
	}

	/** 对应的环境变量名，如 {@code MUGSUN_AI_ENABLED} */
	public String getEnvName() {
		return ModuleSwitch.envName(property);
	}

	/** 已转义、可直接进日志的原值（带单引号） */
	public String getDisplayValue() {
		return displayValue;
	}
}
