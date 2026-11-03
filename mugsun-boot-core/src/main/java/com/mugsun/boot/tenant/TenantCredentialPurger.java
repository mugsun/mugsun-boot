package com.mugsun.boot.tenant;

/**
 * 删租户时清理开放凭据的扩展点：AI 等可选模块实现本接口，由 {@code TenantService} 在同一事务内调用。
 * <p>核心内的 OAuth / API Key 由 TenantService 直接经对应域 Service/Mapper 清理，不走本接口。
 * <p>MCP 恢复（{@code ai_mcp_token} / Bearer {@code msk_…}）时必须：
 * <ol>
 *   <li>鉴权路径 {@code TenantContext.ignore} 查库后调用 {@link TenantValidator#validate}（勿只靠用户 inactive）；</li>
 *   <li>再实现本接口，逻辑删除该租户全部 {@code ai_mcp_token}。</li>
 * </ol>
 */
public interface TenantCredentialPurger {

	/**
	 * 逻辑删除（或等价停用）该租户下本模块的开放凭据。
	 * 调用方已在 {@link TenantContext#ignore} 与删租户同一事务中。
	 */
	void purgeByTenantCode(String tenantCode);
}
