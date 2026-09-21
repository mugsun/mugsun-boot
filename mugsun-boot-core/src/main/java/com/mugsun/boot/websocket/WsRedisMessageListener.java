package com.mugsun.boot.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.constant.WsConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * 扇出订阅：接收广播消息并本地投递；直调会话表，不再经发送门面，避免「投递→再发布」循环。
 */
@Component
@ConditionalOnProperty(name = WsConstants.SENDER_TYPE_PROPERTY, havingValue = WsConstants.SENDER_TYPE_REDIS, matchIfMissing = true)
public class WsRedisMessageListener implements MessageListener {

	private static final Logger log = LoggerFactory.getLogger(WsRedisMessageListener.class);

	private final ObjectMapper objectMapper;
	private final WsSessionManager sessionManager;

	public WsRedisMessageListener(ObjectMapper objectMapper, WsSessionManager sessionManager) {
		this.objectMapper = objectMapper;
		this.sessionManager = sessionManager;
	}

	@Override
	public void onMessage(Message message, byte[] pattern) {
		WsBroadcastMessage msg;
		try {
			msg = objectMapper.readValue(message.getBody(), WsBroadcastMessage.class);
		} catch (Exception e) {
			log.warn("推送广播消息反序列化失败", e);
			return;
		}
		// 逐目标本地投递，单条坏消息不中断后续订阅
		try {
			switch (msg.getTargetType() == null ? "" : msg.getTargetType()) {
				case WsConstants.TARGET_USER -> sessionManager.sendToUser(msg.getUserId(), msg.getFrame());
				case WsConstants.TARGET_TENANT -> sessionManager.sendToTenantUsers(msg.getTenantId(), msg.getFrame());
				case WsConstants.TARGET_ALL -> sessionManager.sendToAll(msg.getFrame());
				case WsConstants.TARGET_CLOSE ->
					sessionManager.closeUserSessions(msg.getUserId(), msg.getTokenValue(), msg.getReason());
				default -> log.warn("未知推送广播目标类型 {}", msg.getTargetType());
			}
		} catch (Exception e) {
			log.warn("推送广播消息本地投递失败 target={}", msg.getTargetType(), e);
		}
	}
}
