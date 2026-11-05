package com.mugsun.boot.ai.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.ai.entity.AiApp;
import com.mugsun.boot.ai.mapper.AiAppMapper;
import com.mugsun.boot.tenant.TenantContext;

import java.util.Set;

/**
 * 审批内嵌 AI：加载并校验已发布 AiApp（租户/状态/类型/能力面）。
 */
public final class AiApprovalAppGate {

	private AiApprovalAppGate() {
	}

	public record GateResult(AiApp app, String errorCode, String errorMsg) {
		public boolean ok() {
			return app != null && errorCode == null;
		}

		public static GateResult ok(AiApp app) {
			return new GateResult(app, null, null);
		}

		public static GateResult fail(String code, String msg) {
			return new GateResult(null, code, msg);
		}
	}

	public static GateResult load(AiAppMapper mapper, ObjectMapper om, Long aiFlowId, String flowTenant) {
		if (aiFlowId == null || aiFlowId <= 0) {
			return GateResult.fail(AiApprovalConstants.ERR_APP_MISSING, "未绑定 AI 应用");
		}
		AiApp app = mapper.selectOneById(aiFlowId);
		if (app == null) {
			AiApp any = TenantContext.ignore(() -> mapper.selectOneById(aiFlowId));
			if (any != null) {
				return GateResult.fail(AiApprovalConstants.ERR_TENANT_DENIED, "跨租户 AI 应用不可用");
			}
			return GateResult.fail(AiApprovalConstants.ERR_APP_MISSING, "AI 应用不存在");
		}
		if (app.getTenantId() == null || app.getTenantId().isBlank()) {
			return GateResult.fail(AiApprovalConstants.ERR_TENANT_DENIED, "AI 应用缺少租户（fail-closed）");
		}
		if (flowTenant != null && !flowTenant.equals(app.getTenantId())) {
			return GateResult.fail(AiApprovalConstants.ERR_TENANT_DENIED, "跨租户 AI 应用不可用");
		}
		if (app.getStatus() == null || app.getStatus() != 1) {
			return GateResult.fail(AiApprovalConstants.ERR_APP_DISABLED, "AI 应用未启用");
		}
		String appType = app.getAppType() == null ? "" : app.getAppType().trim();
		if (!AiApprovalConstants.ALLOWED_APP_TYPES.contains(appType)) {
			return GateResult.fail(AiApprovalConstants.ERR_APP_TYPE_DENIED, "仅支持 workflow/chatflow");
		}
		try {
			Set<String> denied = AiCapabilityGuard.deniedTypes(om, app.getDsl());
			if (!denied.isEmpty()) {
				return GateResult.fail(AiApprovalConstants.ERR_CAPABILITY_DENIED,
					"内嵌禁止节点: " + String.join(",", denied));
			}
		} catch (Exception e) {
			return GateResult.fail(AiApprovalConstants.ERR_EXECUTE, "DSL 解析失败");
		}
		return GateResult.ok(app);
	}
}
