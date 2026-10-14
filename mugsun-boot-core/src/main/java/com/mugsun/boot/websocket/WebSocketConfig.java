package com.mugsun.boot.websocket;

import com.mugsun.boot.common.constant.WsConstants;
import org.springframework.beans.factory.annotation.Value;
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

	private final boolean demoMode;
	private final String demoAllowedOrigins;

	public WebSocketConfig(MessageWebSocketHandler messageWebSocketHandler,
						   WsHandshakeInterceptor wsHandshakeInterceptor,
						   @Value("${mugsun.demo.enabled:false}") boolean demoMode,
						   @Value("${mugsun.demo.ws-allowed-origins:${mugsun.web.front-url:}}") String demoAllowedOrigins) {
		this.messageWebSocketHandler = messageWebSocketHandler;
		this.wsHandshakeInterceptor = wsHandshakeInterceptor;
		this.demoMode = demoMode;
		this.demoAllowedOrigins = demoAllowedOrigins;
	}

	/** demo 下只允许配置的管理端 origin（逗号分隔）；未配置时拒绝一切跨域握手。非 demo 保持原行为 */
	String[] allowedOriginPatterns() {
		if (!demoMode) {
			return new String[]{"*"};
		}
		String[] origins = java.util.Arrays.stream(demoAllowedOrigins == null ? new String[0] : demoAllowedOrigins.split(","))
			.map(String::trim).filter(o -> !o.isEmpty() && !o.equals("*")).toArray(String[]::new);
		return origins;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		// 浏览器跨域直连需要放开 Origin 校验；鉴权由握手拦截器按令牌完成，不依赖同源。demo 下收紧到管理端 origin（安全复审 F7）
		registry.addHandler(messageWebSocketHandler, WsConstants.WS_PATH)
			.addInterceptors(wsHandshakeInterceptor)
			.setAllowedOriginPatterns(allowedOriginPatterns());
	}
}
