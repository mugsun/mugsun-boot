package com.mugsun.boot.rule;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

/**
 * 租户初始化链-节点：建默认管理员
 */
@LiteflowComponent("initAdmin")
public class InitAdminCmp extends NodeComponent {

	@Override
	public void process() {
		getContextBean(RuleContext.class).addStep("初始化默认管理员");
	}
}
