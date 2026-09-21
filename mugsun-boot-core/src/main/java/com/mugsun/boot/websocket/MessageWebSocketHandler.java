package com.mugsun.boot.websocket;

import com.mugsun.boot.common.constant.WsConstants;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 实时推送通道处理器：连接生命周期登记到会话表；入站仅应答心跳，其余消息忽略（预留，当前客户端只收不发）。
 */
@Component
public class MessageWebSocketHandler extends TextWebSocketHandler {

	private final WsSessionManager sessionManager;

	public MessageWebSocketHandler(WsSessionManager sessionManager) {
		this.sessionManager = sessionManager;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		sessionManager.add(session);
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		sessionManager.remove(session);
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) {
		// 传输异常按断连处理，摘除会话，等待客户端重连
		sessionManager.remove(session);
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		if (WsConstants.PING.equals(message.getPayload())) {
			sessionManager.pong(session.getId());
		}
	}
}
