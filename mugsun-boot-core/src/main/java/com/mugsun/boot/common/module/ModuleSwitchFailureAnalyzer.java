package com.mugsun.boot.common.module;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * 模块开关取值非法导致启动失败时，输出干净的 Description / Action，而不是一长串条件求值堆栈。
 * 在 {@code META-INF/spring.factories} 的 {@code org.springframework.boot.diagnostics.FailureAnalyzer} 下注册。
 */
public class ModuleSwitchFailureAnalyzer extends AbstractFailureAnalyzer<InvalidModuleSwitchException> {

	@Override
	protected FailureAnalysis analyze(Throwable rootFailure, InvalidModuleSwitchException cause) {
		String description = "模块开关配置项 " + cause.getProperty() + "（环境变量 " + cause.getEnvName() + "）的值 "
			+ cause.getDisplayValue() + " 不合法。设置后只认 true 或 false（不区分大小写，前后不能有空格），"
			+ "空值、1、0、带首尾空格的值以及 yes/on/no/off 都不接受。";
		String action = "把 " + cause.getEnvName() + "（或 yml 里的 " + cause.getProperty() + "）改成 true 或 false；"
			+ "要保持升级前的关闭状态请写 false。也可以删掉这一项，按默认开启。改完再启动。";
		return new FailureAnalysis(description, action, cause);
	}
}
