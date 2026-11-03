package com.mugsun.boot.ai.tenant;

import com.mugsun.boot.ai.service.AiSecretBizService;
import com.mugsun.boot.tenant.TenantCredentialPurger;
import org.springframework.stereotype.Component;

/**
 * 删租户时级联逻辑删除 ai_secret（可选 AI 模块装配后生效）。
 */
@Component
public class AiSecretTenantPurger implements TenantCredentialPurger {

	private final AiSecretBizService secretBizService;

	public AiSecretTenantPurger(AiSecretBizService secretBizService) {
		this.secretBizService = secretBizService;
	}

	@Override
	public void purgeByTenantCode(String tenantCode) {
		secretBizService.removeByTenantCode(tenantCode);
	}
}
