package com.mugsun.boot.tenant;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.TenantConstants;
import com.mugsun.boot.system.entity.SysTenant;
import com.mugsun.core.tool.exception.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Collections;
import java.util.Set;

/**
 * 租户请求级守卫（鉴权后运行）：对已登录用户依次校验——
 * <ol>
 *   <li>平台超管专属路径（租户/套餐/数据源管理）：非平台租户拒绝（修既有越权）；</li>
 *   <li>租户可用性兜底：会话中途被停用/过期的租户，后续请求一律拒绝（缓存 60s）；</li>
 *   <li>租户号一致性：请求头 {@code X-Tenant-Id} 与会话租户不符则拒绝（防伪造越权）；</li>
 *   <li>套餐服务端强制：请求命中套餐外功能模块则拒绝（请求期动态强制，非仅前端隐藏）。</li>
 * </ol>
 * 平台租户 {@code 000000}（超管）豁免 2/3/4；跨租户查看走会话 {@code switch} 机制，不信任请求头。
 */
public class TenantGuardInterceptor implements HandlerInterceptor {

	private final TenantValidator validator;

	public TenantGuardInterceptor(TenantValidator validator) {
		this.validator = validator;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!StpUtil.isLogin()) {
			return true;
		}
		String path = request.getRequestURI();
		Object t = StpUtil.getSession().get(TenantContext.TENANT_SESSION_KEY);
		String sessionTenant = t == null ? null : t.toString();
		// 平台超管 = 平台租户 000000 + 内置 admin 角色（区别于自助注册落 000000 的无角色用户，杜绝越权升格）
		boolean isSuperAdmin = TenantContext.isPlatformSuperAdmin();
		boolean isPlatformTenant = TenantConstants.DEFAULT_TENANT_ID.equals(sessionTenant);

		// 1) 平台超管专属路径：仅平台超管可访问（平台租户内的无角色用户亦拒）
		if (TenantPackageModules.isPlatformOnly(path) && !isSuperAdmin) {
			throw new ForbiddenException("仅平台超管可访问平台级资源");
		}
		// 1b) 平台超管专属写路径：菜单/字典/参数为平台全局表，非 GET 写请求仅平台超管（GET 只读放行）
		if (!"GET".equals(request.getMethod()) && TenantPackageModules.isPlatformWrite(path) && !isSuperAdmin) {
			throw new ForbiddenException("仅平台超管可修改平台全局配置");
		}
		// 平台租户不受生命周期/套餐/一致性限制（其专属路径已在上一步按超管门控）；异常空会话交回权限层
		if (isPlatformTenant || sessionTenant == null || sessionTenant.isBlank()) {
			return true;
		}

		// 2) 租户可用性兜底
		SysTenant tenant = validator.loadTenant(sessionTenant);
		String reason = TenantValidator.invalidReason(tenant);
		if (reason != null) {
			throw new ForbiddenException(reason);
		}

		// 3) 租户号一致性（防伪造头）
		String header = request.getHeader(TenantConstants.TENANT_HEADER);
		if (header != null && !header.isBlank() && !header.equals(sessionTenant)) {
			throw new ForbiddenException("无权跨租户操作");
		}

		// 4) 套餐服务端强制
		if (tenant.getPackageId() != null) {
			Set<String> required = TenantPackageModules.gatedRouteNames(path);
			if (required != null) {
				Set<String> allowed = validator.loadPackageKeys(tenant.getPackageId());
				if (allowed != null && !allowed.isEmpty() && Collections.disjoint(required, allowed)) {
					throw new ForbiddenException("该功能不在您的套餐内，请联系管理员开通");
				}
			}
		}
		return true;
	}
}
