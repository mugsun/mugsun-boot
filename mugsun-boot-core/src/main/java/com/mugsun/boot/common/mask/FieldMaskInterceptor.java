package com.mugsun.boot.common.mask;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 字段脱敏拦截器：请求进入时一次性解析当前用户权限码快照，请求结束清理。
 * <p>在业务查询之前解析（早于结果集映射），使脱敏处理器只读快照、不触发嵌套查询。
 */
public class FieldMaskInterceptor implements HandlerInterceptor {

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (StpUtil.isLogin()) {
			FieldMaskContext.resolve(StpUtil.getPermissionList());
		}
		return true;
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
		FieldMaskContext.clear();
	}
}
