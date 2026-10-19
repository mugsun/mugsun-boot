package com.mugsun.boot.common.module;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 按 {@link ModuleSwitch#parseStrict} 读取 yml / 环境变量开关决定是否装配：属性不存在按开启，只认 true/false（不区分大小写、不带空格），
 * 其它值（含空值、1/0）启动失败，而不是悄悄开启或关闭模块。
 * <p>例：{@code @ConditionalOnModuleSwitch("mugsun.ai.enabled")}。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(ModuleSwitchCondition.class)
public @interface ConditionalOnModuleSwitch {

	/** 完整属性名，如 {@code mugsun.ai.enabled} */
	String value();
}
