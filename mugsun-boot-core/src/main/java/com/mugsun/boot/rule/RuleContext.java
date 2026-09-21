package com.mugsun.boot.rule;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则链上下文：记录各节点执行轨迹。
 */
public class RuleContext {

	private final List<String> steps = new ArrayList<>();

	public void addStep(String step) {
		steps.add(step);
	}

	public List<String> getSteps() {
		return steps;
	}
}
