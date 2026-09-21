package com.mugsun.boot.tenant;

import com.mybatisflex.core.tenant.TenantFactory;

/**
 * 租户工厂：委派 {@link TenantContext#resolveTenantIds()} 统一解析当前租户条件
 * （会话租户 / 超管切换 / 异步透传 / ignore 作用域 / 开放接口令牌租户）。
 * <p>返回 {@code null} 不施加租户条件；无任何上下文时由 {@link TenantContext} fail-closed 抛异常，杜绝静默全量越权。
 */
public class SaTokenTenantFactory implements TenantFactory {

	@Override
	public Object[] getTenantIds() {
		return TenantContext.resolveTenantIds();
	}

	@Override
	public Object[] getTenantIds(String tableName) {
		return TenantContext.resolveTenantIds();
	}
}
