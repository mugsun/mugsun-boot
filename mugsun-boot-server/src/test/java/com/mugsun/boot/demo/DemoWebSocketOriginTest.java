package com.mugsun.boot.demo;

import com.mugsun.boot.websocket.MessageWebSocketHandler;
import com.mugsun.boot.websocket.WebSocketConfig;
import com.mugsun.boot.websocket.WsHandshakeInterceptor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 安全复审 F7：demo 下 WebSocket 只允许配置的管理端 origin，「*」被丢弃；非 demo 行为不变 */
class DemoWebSocketOriginTest {

	private static String[] origins(boolean demo, String cfg) throws Exception {
		WebSocketConfig c = new WebSocketConfig(mock(MessageWebSocketHandler.class), mock(WsHandshakeInterceptor.class), demo, cfg);
		Method m = WebSocketConfig.class.getDeclaredMethod("allowedOriginPatterns");
		m.setAccessible(true);
		return (String[]) m.invoke(c);
	}

	@Test
	void demoRestrictsOrigins() throws Exception {
		assertThat(origins(false, "https://pc.example")).containsExactly("*");
		assertThat(origins(true, "https://pc.example, http://1.2.3.4:3006")).containsExactly("https://pc.example", "http://1.2.3.4:3006");
		assertThat(origins(true, "*")).isEmpty();
		assertThat(origins(true, "")).isEmpty();
	}
}
