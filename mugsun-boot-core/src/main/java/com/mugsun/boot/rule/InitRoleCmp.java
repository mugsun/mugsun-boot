package com.mugsun.boot.rule;

import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

/**
 * 租户初始化链-节点：建默认角色
 */
@LiteflowComponent("initRole")
public class InitRoleCmp extends NodeComponent {

	@Override
	public void process() {
		getContextBean(RuleContext.class).addStep("初始化默认角色");
	}
}
