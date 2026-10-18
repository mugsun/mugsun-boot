package com.mugsun.boot.common.module;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/**
 * {@link ConditionalOnModuleSwitch} 的实现，按 {@link ModuleSwitch#parseStrict} 严格解析。非法值抛 {@link InvalidModuleSwitchException}，
 * Spring 会把它包成启动失败，{@link ModuleSwitchFailureAnalyzer} 给出改法。
 */
public class ModuleSwitchCondition extends SpringBootCondition {

	@Override
	public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
		Map<String, Object> attrs = metadata.getAnnotationAttributes(ConditionalOnModuleSwitch.class.getName());
		String name = (String) attrs.get("value");
		String raw = context.getEnvironment().getProperty(name);
		ModuleSwitch.State state = ModuleSwitch.parseStrict(raw);
		if (!state.legal()) {
			throw new InvalidModuleSwitchException(name, raw);
		}
		String msg = name + "=" + ModuleSwitch.quote(raw) + " -> " + state;
		return state.enabled() ? ConditionOutcome.match(msg) : ConditionOutcome.noMatch(msg);
	}
}
