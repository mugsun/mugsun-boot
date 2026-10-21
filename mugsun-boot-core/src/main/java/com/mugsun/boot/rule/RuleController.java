package com.mugsun.boot.rule;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.flow.LiteflowResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mugsun.boot.tenant.TenantContext;

/**
 * 规则编排：执行 LiteFlow 链，返回节点执行轨迹。
 */
@RestController
@RequestMapping("/system/rule")
@SaCheckLogin
public class RuleController {

	private final FlowExecutor flowExecutor;

	public RuleController(FlowExecutor flowExecutor) {
		this.flowExecutor = flowExecutor;
	}

	/** 执行租户初始化编排链 */
	@PostMapping("/tenant-init")
	@cn.dev33.satoken.annotation.SaCheckPermission("sys:rule:tenant-init")
	public R<List<String>> tenantInit() {
		// 只允许平台超管（平台租户 000000 + admin 角色）；租户管理员即使持码也拒
		TenantContext.requirePlatformSuperAdmin();
		LiteflowResponse response = flowExecutor.execute2Resp("tenantInitChain", null, RuleContext.class);
		if (!response.isSuccess()) {
			throw new ServiceException("规则链执行失败：" + response.getMessage());
		}
		return R.data(response.getContextBean(RuleContext.class).getSteps());
	}
}
