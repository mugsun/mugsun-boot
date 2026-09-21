package com.mugsun.boot.websocket;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.WsConstants;
import com.mugsun.boot.tenant.TenantContext;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * 握手鉴权：query 携带登录令牌（?token=xxx），校验通过才把用户/租户/令牌写入会话属性；
 * 无效令牌直接拒绝握手，不产生推送会话。
 */
@Component
public class WsHandshakeInterceptor implements HandshakeInterceptor {

	private final com.mugsun.boot.tenant.TenantValidator tenantValidator;

	public WsHandshakeInterceptor(com.mugsun.boot.tenant.TenantValidator tenantValidator) {
		this.tenantValidator = tenantValidator;
	}

	@Override
	public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
								   WebSocketHandler wsHandler, Map<String, Object> attributes) {
		String token = UriComponentsBuilder.fromUri(request.getURI()).build()
			.getQueryParams().getFirst(WsConstants.TOKEN_PARAM);
		if (token == null || token.isBlank()) {
			return false;
		}
		Object loginId = StpUtil.getLoginIdByToken(token);
		if (loginId == null) {
			return false;
		}
		Long userId;
		try {
			userId = Long.valueOf(loginId.toString());
		} catch (NumberFormatException e) {
			// 非数字账号（非常规登录）不接入实时推送
			return false;
		}
		attributes.put(WsConstants.ATTR_USER_ID, userId);
		// 租户编号取不到时缺省（属性表不接受 null 值），仅影响按租户推送的匹配
		String tenantId = resolveTenantId(userId);
		if (tenantId != null) {
			// 握手即校验租户生命周期：停用/过期/已删租户的用户连接一律拒绝（防停用租户持续收推送）
			String tenantInvalid = tenantValidator.validate(tenantId);
			if (tenantInvalid != null) {
				return false;
			}
			attributes.put(WsConstants.ATTR_TENANT_ID, tenantId);
		}
		attributes.put(WsConstants.ATTR_TOKEN_VALUE, token);
		return true;
	}

	@Override
	public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
							   WebSocketHandler wsHandler, Exception exception) {
	}

	/** 从账号会话读租户编号（登录时写入，见 AuthController#login）；会话缺失/未写入时返回 null */
	private String resolveTenantId(Long userId) {
		try {
			SaSession session = StpUtil.getSessionByLoginId(userId, false);
			if (session == null) {
				return null;
			}
			Object tenantId = session.get(TenantContext.TENANT_SESSION_KEY);
			return tenantId == null ? null : tenantId.toString();
		} catch (Exception e) {
			return null;
		}
	}
}
