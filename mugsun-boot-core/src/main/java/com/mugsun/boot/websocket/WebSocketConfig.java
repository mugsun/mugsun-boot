package com.mugsun.boot.websocket;

import com.mugsun.boot.common.constant.WsConstants;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 端点装配：实时推送通道（站内信/公告/强制下线），握手鉴权见 {@link WsHandshakeInterceptor}。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	private final MessageWebSocketHandler messageWebSocketHandler;
	private final WsHandshakeInterceptor wsHandshakeInterceptor;

	public WebSocketConfig(MessageWebSocketHandler messageWebSocketHandler,
						   WsHandshakeInterceptor wsHandshakeInterceptor) {
		this.messageWebSocketHandler = messageWebSocketHandler;
		this.wsHandshakeInterceptor = wsHandshakeInterceptor;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		// 浏览器跨域直连需要放开 Origin 校验；鉴权由握手拦截器按令牌完成，不依赖同源
		registry.addHandler(messageWebSocketHandler, WsConstants.WS_PATH)
			.addInterceptors(wsHandshakeInterceptor)
			.setAllowedOriginPatterns("*");
	}
}
