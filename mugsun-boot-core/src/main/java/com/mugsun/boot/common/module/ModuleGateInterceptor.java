package com.mugsun.boot.common.module;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.function.BooleanSupplier;

/**
 * 可选模块请求闸门：模块关闭时，其接口与「从未注册」一样返回 404。
 * <p>yml 开关（{@code mugsun.<f>.enabled=false}）关闭时 Controller 根本不注册，天然 404；
 * 但只关系统参数（{@code <f>.module.enabled=false}）时 Controller 仍在容器里，过去要靠各接口自己 {@code requireEnabled()} 报「模块未启用」，
 * 漏写的接口照样可用。本拦截器每个请求都按 {@link AiModuleStatus}/{@link GisModuleStatus}/{@link TrackModuleStatus}
 * 的 {@code enabled()}（yml ∩ sys_param）判定，两种关法对外表现统一为 404。
 * <p>抛 {@link NoResourceFoundException} 而不是自己写响应：与未映射路径走同一条异常解析链，响应体与状态码完全一致。
 */
public class ModuleGateInterceptor implements HandlerInterceptor {

	private final String module;
	private final BooleanSupplier enabled;

	public ModuleGateInterceptor(String module, BooleanSupplier enabled) {
		this.module = module;
		this.enabled = enabled;
	}

	/** 模块标识（ai / gis / track），仅用于排查 */
	public String module() {
		return module;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
		throws NoResourceFoundException {
		if (enabled.getAsBoolean()) {
			return true;
		}
		String path = request.getRequestURI().substring(request.getContextPath().length());
		throw new NoResourceFoundException(HttpMethod.valueOf(request.getMethod()), path);
	}
}
