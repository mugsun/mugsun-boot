package com.mugsun.boot.websocket;

import com.mugsun.boot.common.constant.WsConstants;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * 单机直发：仅操作本节点会话表，供本地调试；集群部署使用 Redis 扇出实现。
 */
@Component
@ConditionalOnProperty(name = WsConstants.SENDER_TYPE_PROPERTY, havingValue = WsConstants.SENDER_TYPE_LOCAL)
public class LocalWsMessageSender implements WsMessageSender {

	private final WsSessionManager sessionManager;

	public LocalWsMessageSender(WsSessionManager sessionManager) {
		this.sessionManager = sessionManager;
	}

	@Override
	public void sendToUser(Long userId, WsFrame frame) {
		sessionManager.sendToUser(userId, frame);
	}

	@Override
	public void sendToUsers(Collection<Long> userIds, WsFrame frame) {
		if (userIds == null) {
			return;
		}
		userIds.forEach(userId -> sessionManager.sendToUser(userId, frame));
	}

	@Override
	public void sendToTenant(String tenantId, WsFrame frame) {
		sessionManager.sendToTenantUsers(tenantId, frame);
	}

	@Override
	public void sendToAll(WsFrame frame) {
		sessionManager.sendToAll(frame);
	}

	@Override
	public void closeUser(Long userId, String tokenValue, String reason) {
		sessionManager.closeUserSessions(userId, tokenValue, reason);
	}
}
