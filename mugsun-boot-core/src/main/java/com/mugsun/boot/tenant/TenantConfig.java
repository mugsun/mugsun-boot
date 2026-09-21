package com.mugsun.boot.tenant;

import com.mybatisflex.core.tenant.TenantManager;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * 多租户装配：注册基于会话的租户工厂。
 */
@Configuration
public class TenantConfig {

	@PostConstruct
	public void init() {
		TenantManager.setTenantFactory(new SaTokenTenantFactory());
	}
}
