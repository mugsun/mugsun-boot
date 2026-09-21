package com.mugsun.boot.websocket;

import com.mugsun.boot.common.constant.WsConstants;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 扇出订阅装配：仅集群模式（sender-type=redis）启动 Pub/Sub 监听容器。
 */
@Configuration
@ConditionalOnProperty(name = WsConstants.SENDER_TYPE_PROPERTY, havingValue = WsConstants.SENDER_TYPE_REDIS, matchIfMissing = true)
public class WsRedisSubscriberConfig {

	@Bean
	public RedisMessageListenerContainer wsRedisMessageListenerContainer(
			RedisConnectionFactory connectionFactory, WsRedisMessageListener listener) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		container.addMessageListener(listener, new ChannelTopic(WsConstants.REDIS_CHANNEL));
		return container;
	}
}
