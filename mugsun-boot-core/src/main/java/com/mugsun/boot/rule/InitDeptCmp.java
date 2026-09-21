package com.mugsun.boot.rule;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

/**
 * 租户初始化链-节点：建默认部门
 */
@LiteflowComponent("initDept")
public class InitDeptCmp extends NodeComponent {

	@Override
	public void process() {
		getContextBean(RuleContext.class).addStep("初始化默认部门");
	}
}
