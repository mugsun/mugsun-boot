package com.mugsun.boot.common.module;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/**
 * {@link ConditionalOnModuleSwitch} 的实现。非法值抛 {@link IllegalStateException}，Spring 会把它包成启动失败。
 */
public class ModuleSwitchCondition extends SpringBootCondition {

	private static final Logger log = LoggerFactory.getLogger(ModuleSwitchCondition.class);

	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		Map<String, Object> attrs = metadata.getAnnotationAttributes(ConditionalOnModuleSwitch.class.getName());
		String name = (String) attrs.get("value");
		String raw = context.getEnvironment().getProperty(name);
		ModuleSwitch.State state = ModuleSwitch.parse(raw);
		if (!state.legal()) {
			throw new IllegalStateException("配置项 " + name + " 的值 " + ModuleSwitch.quote(raw) + " 不合法："
				+ ModuleSwitch.MSG_INVALID + "。请检查 yml 或对应的环境变量（如 MUGSUN_AI_ENABLED）");
		}
		if (state.deprecated()) {
			log.warn("配置项 {} 写成了 {}：{}", name, ModuleSwitch.quote(raw), ModuleSwitch.MSG_DEPRECATED);
		}
		String msg = name + "=" + ModuleSwitch.quote(raw) + " -> " + state;
		return state.enabled() ? ConditionOutcome.match(msg) : ConditionOutcome.noMatch(msg);
	}
}
