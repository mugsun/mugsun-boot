package com.mugsun.boot.common.perm;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 权限兜底守卫（fail-closed）：对 /system/** 的写请求（POST/PUT/DELETE），
 * 若处理器<b>未</b>显式声明 {@link SaCheckPermission}/{@link SaCheckRole}/{@link SaIgnore}、且非豁免个人/共享接口，
 * 则按路径派生权限码强制校验——「受控写接口默认有码才放行」。
 * <p>管理员持通配 {@code *} 恒通过，故本兜底只拦无对应码的非管理员，杜绝裸奔写端点的越权。
 * 显式带 {@link SaCheckPermission} 的端点交由 Sa-Token 原生校验，本守卫不重复拦截。
 */
public class PermissionGuardInterceptor implements HandlerInterceptor {

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!(handler instanceof HandlerMethod hm)) {
			return true;
		}
		String method = request.getMethod();
		if (!("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method))) {
			return true;
		}
		// 已显式声明权限/角色校验或忽略的端点，交由 Sa-Token 原生处理，避免重复/冲突
		if (hasAnnotation(hm, SaCheckPermission.class) || hasAnnotation(hm, SaCheckRole.class)
			|| hasAnnotation(hm, SaIgnore.class)) {
			return true;
		}
		if (!StpUtil.isLogin()) {
			return true;
		}
		String path = request.getRequestURI();
		if (PermissionModules.isExempt(path)) {
			return true;
		}
		String code = PermissionModules.deriveCode(path);
		if (code != null) {
			// 无码即拒（管理员 * 通配放行）；抛 NotPermissionException 由全局处理器转真 403
			StpUtil.checkPermission(code);
		}
		return true;
	}

	private boolean hasAnnotation(HandlerMethod hm, Class<? extends java.lang.annotation.Annotation> anno) {
		return hm.getMethodAnnotation(anno) != null || hm.getBeanType().isAnnotationPresent(anno);
	}
}
