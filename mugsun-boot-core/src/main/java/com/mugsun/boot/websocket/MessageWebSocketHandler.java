package com.mugsun.boot.websocket;

import com.mugsun.boot.common.constant.WsConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 实时推送通道处理器：连接生命周期登记到会话表；入站仅应答心跳，其余消息忽略（预留，当前客户端只收不发）。
 * <p>只读演示模式（mugsun.demo.enabled=true）下除心跳应答（纯内存回 pong，不落库）外的入站消息一律丢弃，通道只用于服务端推送。
 */
@Component
public class MessageWebSocketHandler extends TextWebSocketHandler {

	private final WsSessionManager sessionManager;
	private final boolean demoMode;

	public MessageWebSocketHandler(WsSessionManager sessionManager,
								   @Value("${mugsun.demo.enabled:false}") boolean demoMode) {
		this.sessionManager = sessionManager;
		this.demoMode = demoMode;
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
			return;
		}
		if (demoMode) {
			// 演示环境只读：丢弃一切业务入站消息（即使以后在此增加处理逻辑，demo 下也不会执行）
			return;
		}
	}
}
