package com.mugsun.boot.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.tenant.TenantValidator;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.api.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 开放接口守卫：校验 Bearer 令牌与 scope，放行/拒绝均留痕。
 * 无效令牌 401，scope 不足 403；令牌租户已删/停用/过期也拒绝（401/403）。
 */
public class OpenApiInterceptor implements HandlerInterceptor {

	private final OAuthService oauthService;
	private final OAuthLogService logService;
	private final ObjectMapper objectMapper;
	private final OpenApiSignService signService;
	private final TenantValidator tenantValidator;

	public OpenApiInterceptor(OAuthService oauthService, OAuthLogService logService, ObjectMapper objectMapper,
							  OpenApiSignService signService, TenantValidator tenantValidator) {
		this.oauthService = oauthService;
		this.logService = logService;
		this.objectMapper = objectMapper;
		this.signService = signService;
		this.tenantValidator = tenantValidator;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		if (!(handler instanceof HandlerMethod method)) {
			return true;
		}
		String path = request.getRequestURI();
		String ip = request.getRemoteAddr();
		String required = requiredScope(method);

		OAuthService.TokenInfo info = oauthService.resolveToken(bearerToken(request));
		if (info == null) {
			logService.record("-", path, required, 0, ip, "令牌无效或已过期");
			return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "令牌无效或已过期");
		}
		// 签名 + 防重放（HMAC + nonce + timestamp）：拦篡改/重放
		String signError = signService.verify(request, info.clientId());
		if (signError != null) {
			logService.record(info.clientId(), path, required, 0, ip, signError);
			return reject(response, HttpServletResponse.SC_UNAUTHORIZED, signError);
		}
		if (required != null && !info.scopes().contains(required)) {
			logService.record(info.clientId(), path, required, 0, ip, "缺少授权范围：" + required);
			return reject(response, HttpServletResponse.SC_FORBIDDEN, "缺少授权范围：" + required);
		}
		// 租户可用性：删除/停用/过期后存量令牌不得继续调 /open/**
		String tenantId = info.tenantId();
		if (tenantId != null && !tenantId.isBlank()) {
			String reason = tenantValidator.validate(tenantId);
			if (reason != null) {
				int status = "租户不存在".equals(reason)
					? HttpServletResponse.SC_UNAUTHORIZED
					: HttpServletResponse.SC_FORBIDDEN;
				logService.record(info.clientId(), path, required, 0, ip, reason);
				return reject(response, status, reason);
			}
			// 令牌租户写入统一上下文，供 SaTokenTenantFactory 对开放接口按令牌租户隔离（防跨租户越权）。
			TenantContext.set(tenantId);
		}
		logService.record(info.clientId(), path, required, 1, ip, "放行");
		request.setAttribute("oauthClientId", info.clientId());
		return true;
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
		TenantContext.clear();
	}

	private String requiredScope(HandlerMethod method) {
		OpenScope scope = method.getMethodAnnotation(OpenScope.class);
		return scope == null ? null : scope.value();
	}

	private String bearerToken(HttpServletRequest request) {
		String header = request.getHeader("Authorization");
		if (header == null || header.isBlank()) {
			return null;
		}
		return header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : header.trim();
	}

	private boolean reject(HttpServletResponse response, int status, String msg) throws Exception {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		ResultCode code = status == HttpServletResponse.SC_UNAUTHORIZED
			? ResultCode.UNAUTHORIZED
			: status == HttpServletResponse.SC_FORBIDDEN ? ResultCode.FORBIDDEN : ResultCode.FAILURE;
		response.getWriter().write(objectMapper.writeValueAsString(R.fail(code, msg)));
		return false;
	}
}
