package com.mugsun.boot.common.setting;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * 枚举型配置取值非法导致启动失败时，输出干净的 Description / Action（与模块开关的 ModuleSwitchFailureAnalyzer 同一做法）。
 * 在 {@code META-INF/spring.factories} 的 {@code org.springframework.boot.diagnostics.FailureAnalyzer} 下注册。
 */
public class InvalidSettingFailureAnalyzer extends AbstractFailureAnalyzer<InvalidSettingException> {

	@Override
	protected FailureAnalysis analyze(Throwable rootFailure, InvalidSettingException cause) {
		String description = "配置项 " + cause.getProperty() + "（环境变量 " + cause.getEnvName() + "）的值 "
			+ cause.getDisplayValue() + " 不合法：" + cause.getReason();
		String action = "改 " + cause.getEnvName() + "（或 yml 里的 " + cause.getProperty() + "）后再启动；"
			+ "也可以删掉这一项，按默认值启动。";
		return new FailureAnalysis(description, action, cause);
	}
}
