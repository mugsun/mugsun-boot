package com.mugsun.boot.demo;

import com.mugsun.boot.common.constant.WsConstants;
import com.mugsun.boot.websocket.MessageWebSocketHandler;
import com.mugsun.boot.websocket.WsSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 演示模式下 WebSocket 入站业务消息被丢弃，只保留心跳应答 */
class DemoWebSocketDropTest {

	@Test
	void demoDropsInboundMessagesButKeepsHeartbeat() throws Exception {
		WsSessionManager manager = mock(WsSessionManager.class);
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.getId()).thenReturn("s1");
		MessageWebSocketHandler handler = new MessageWebSocketHandler(manager, true);

		handler.handleMessage(session, new TextMessage("{\"type\":\"chat\",\"text\":\"hi\"}"));
		verifyNoInteractions(manager);

		handler.handleMessage(session, new TextMessage(WsConstants.PING));
		verify(manager).pong("s1");
		verifyNoMoreInteractions(manager);
	}
}
