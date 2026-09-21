package com.mugsun.boot.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.common.constant.WsConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本节点在线会话表：sessionId / userId 双索引；全部下行经装饰会话串行化发送（超时 + 缓冲上限保护）。
 * 集群下各节点只持有本机连接，跨节点投递经 Redis 扇出（见 WsRedisMessageListener）。
 */
@Component
public class WsSessionManager {

	private static final Logger log = LoggerFactory.getLogger(WsSessionManager.class);

	/** sessionId → 装饰会话 */
	private final ConcurrentHashMap<String, WebSocketSession> idSessions = new ConcurrentHashMap<>();
	/** userId → 该用户各端会话（多端在线） */
	private final ConcurrentHashMap<Long, CopyOnWriteArrayList<WebSocketSession>> userSessions = new ConcurrentHashMap<>();

	private final ObjectMapper objectMapper;

	public WsSessionManager(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/** 连接建立：包装为并发安全会话（发送串行化 + 超时/缓冲保护）后登记双索引 */
	public void add(WebSocketSession session) {
		// 装饰器超时参数为 int，常量按毫秒语义保留 long
		WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(
			session, (int) WsConstants.SEND_TIMEOUT_MS, WsConstants.SEND_BUFFER_BYTES);
		idSessions.put(session.getId(), decorated);
		Object userId = session.getAttributes().get(WsConstants.ATTR_USER_ID);
		if (userId instanceof Long uid) {
			userSessions.computeIfAbsent(uid, k -> new CopyOnWriteArrayList<>()).add(decorated);
		}
	}

	/** 连接关闭/异常：摘除双索引（按 sessionId 匹配，容忍重复调用） */
	public void remove(WebSocketSession session) {
		idSessions.remove(session.getId());
		Object userId = session.getAttributes().get(WsConstants.ATTR_USER_ID);
		if (userId instanceof Long uid) {
			CopyOnWriteArrayList<WebSocketSession> list = userSessions.get(uid);
			if (list != null) {
				list.removeIf(s -> s.getId().equals(session.getId()));
				if (list.isEmpty()) {
					userSessions.remove(uid, list);
				}
			}
		}
	}

	/** 心跳应答：经装饰会话串行发出，避免与下行推送并发写冲突 */
	public void pong(String sessionId) {
		WebSocketSession session = idSessions.get(sessionId);
		if (session != null) {
			sendQuietly(session, WsConstants.PONG);
		}
	}

	/** 推给指定用户的全部在线端 */
	public void sendToUser(Long userId, WsFrame frame) {
		List<WebSocketSession> list = userId == null ? null : userSessions.get(userId);
		if (list == null) {
			return;
		}
		String json = serialize(frame);
		if (json == null) {
			return;
		}
		for (WebSocketSession session : list) {
			sendQuietly(session, json);
		}
	}

	/** 推给指定租户的全部在线连接（按握手时写入的租户属性匹配） */
	public void sendToTenantUsers(String tenantId, WsFrame frame) {
		if (tenantId == null) {
			return;
		}
		String json = serialize(frame);
		if (json == null) {
			return;
		}
		for (WebSocketSession session : idSessions.values()) {
			if (tenantId.equals(session.getAttributes().get(WsConstants.ATTR_TENANT_ID))) {
				sendQuietly(session, json);
			}
		}
	}

	/** 推给本节点全部在线连接 */
	public void sendToAll(WsFrame frame) {
		String json = serialize(frame);
		if (json == null) {
			return;
		}
		for (WebSocketSession session : idSessions.values()) {
			sendQuietly(session, json);
		}
	}

	/** 强制下线：先发强制下线帧再关闭；tokenValue 非空按令牌踢单端，否则按 userId 踢全端 */
	public void closeUserSessions(Long userId, String tokenValue, String reason) {
		List<WebSocketSession> targets;
		if (tokenValue != null && !tokenValue.isBlank()) {
			targets = idSessions.values().stream()
				.filter(s -> tokenValue.equals(s.getAttributes().get(WsConstants.ATTR_TOKEN_VALUE)))
				.toList();
		} else if (userId != null) {
			List<WebSocketSession> list = userSessions.get(userId);
			targets = list == null ? List.of() : List.copyOf(list);
		} else {
			return;
		}
		if (targets.isEmpty()) {
			return;
		}
		String json = serialize(
			WsFrame.of(WsConstants.FORCE_OFFLINE, Map.of("reason", reason == null ? "" : reason)));
		for (WebSocketSession session : targets) {
			if (json != null) {
				sendQuietly(session, json);
			}
			try {
				session.close(CloseStatus.NORMAL);
			} catch (IOException e) {
				log.debug("关闭推送会话失败 sid={}", session.getId(), e);
			}
			remove(session);
		}
	}

	/** 逐连接静默发送：IO 失败即摘除该端（客户端重连自愈），绝不向上抛出 */
	private void sendQuietly(WebSocketSession session, String text) {
		if (!session.isOpen()) {
			remove(session);
			return;
		}
		try {
			session.sendMessage(new TextMessage(text));
		} catch (Exception e) {
			log.debug("推送帧发送失败，摘除会话 sid={}", session.getId(), e);
			remove(session);
		}
	}

	private String serialize(WsFrame frame) {
		try {
			return objectMapper.writeValueAsString(frame);
		} catch (Exception e) {
			log.warn("推送帧序列化失败 type={}", frame == null ? null : frame.getType(), e);
			return null;
		}
	}
}
