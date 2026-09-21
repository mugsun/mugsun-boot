package com.mugsun.boot.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.constant.WsConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * 集群扇出：推送意图序列化为广播消息投到 Redis Pub/Sub，各节点（含本节点）订阅后本地投递。
 */
@Component
@ConditionalOnProperty(name = WsConstants.SENDER_TYPE_PROPERTY, havingValue = WsConstants.SENDER_TYPE_REDIS, matchIfMissing = true)
public class RedisWsMessageSender implements WsMessageSender {

	private static final Logger log = LoggerFactory.getLogger(RedisWsMessageSender.class);

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	public RedisWsMessageSender(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	@Override
	public void sendToUser(Long userId, WsFrame frame) {
		publish(WsBroadcastMessage.ofUser(userId, frame));
	}

	@Override
	public void sendToUsers(Collection<Long> userIds, WsFrame frame) {
		if (userIds == null) {
			return;
		}
		userIds.forEach(userId -> sendToUser(userId, frame));
	}

	@Override
	public void sendToTenant(String tenantId, WsFrame frame) {
		publish(WsBroadcastMessage.ofTenant(tenantId, frame));
	}

	@Override
	public void sendToAll(WsFrame frame) {
		publish(WsBroadcastMessage.ofAll(frame));
	}

	@Override
	public void closeUser(Long userId, String tokenValue, String reason) {
		publish(WsBroadcastMessage.ofClose(userId, tokenValue, reason));
	}

	/** 发布失败仅告警：推送是增强能力，不拖垮业务主流程 */
	private void publish(WsBroadcastMessage message) {
		try {
			redisTemplate.convertAndSend(WsConstants.REDIS_CHANNEL, objectMapper.writeValueAsString(message));
		} catch (Exception e) {
			log.warn("推送广播消息发布失败 target={}", message.getTargetType(), e);
		}
	}
}
