package com.mugsun.boot.security;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UrlPathHelper;

/**
 * 全局登录守卫（#93）：对 {@link HandlerMethod} 默认 {@link StpUtil#checkLogin()}，
 * 仅 {@link LoginPublicPaths} 放行。
 * <p>路径先经 {@link UrlPathHelper#getLookupPathForRequest} 归一，与 #94 Actuator 及 Spring 映射一致。
 * 非 HandlerMethod（静态资源映射、未落控制器的 404 等）不拦——避免匿名探未注册路径先收到 401、
 * 与 ModuleGate「未注册=404」口径冲突；{@code /file/**} 仍由 {@code LocalFileAccessFilter} 守卫。
 * 控制器上原有 {@code @SaCheckLogin} 保留作双保险；{@code @SaIgnore} 端点须同时列入白名单。
 */
public class LoginAuthInterceptor implements HandlerInterceptor {

	private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (request.getDispatcherType() != DispatcherType.REQUEST) {
			return true;
		}
		if (!(handler instanceof HandlerMethod)) {
			return true;
		}
		// CORS 预检由 CorsFilter 处理；此处放行避免误拦
		if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
			return true;
		}
		String path = PATH_HELPER.getLookupPathForRequest(request);
		if (LoginPublicPaths.isPublic(path)) {
			return true;
		}
		StpUtil.checkLogin();
		return true;
	}
}
