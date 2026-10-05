package com.mugsun.boot.common.perm;

import cn.dev33.satoken.exception.SaTokenContextException;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 接口权限守卫：请求命中已启用规则时，除平台管理员外必须持有被授权的角色。
 */
public class ApiScopeGuard implements HandlerInterceptor {

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		boolean loggedIn;
		try {
			loggedIn = StpUtil.isLogin();
		} catch (SaTokenContextException e) {
			return true;
		}
		if (!loggedIn || TenantContext.isPlatformSuperAdmin()) {
			return true;
		}
		ApiScopeIndex.Rule rule = ApiScopeIndex.match(request.getRequestURI());
		if (rule == null) {
			return true;
		}
		if (ApiScopeIndex.allowed(rule.id(), TenantContext.current(), StpUtil.getRoleList())) {
			return true;
		}
		throw new ForbiddenException("没有接口「" + rule.name() + "」的访问权限。请让管理员在接口权限中把该路径授给当前角色");
	}
}
