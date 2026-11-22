package com.mugsun.boot.ai.springai;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

/**
 * Spring AI 的装配。只有一个 Bean：{@code ChatClient.Builder}，用法和 Spring AI 官方文档一样，注入后 {@code build()}。
 * <p>这个模块没有引 Spring AI 的自动装配包，所以 {@code spring.ai.*} 配置不起作用；模型在「大模型配置」页面上配。
 */
@Configuration(proxyBeanMethods = false)
public class MugsunSpringAiConfiguration {

	/** 每次注入都是新的一份，各处可以各自加默认提示词、工具，互不影响 */
	@Bean
	@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
	@ConditionalOnMissingBean
	public ChatClient.Builder chatClientBuilder(MugsunChatModel chatModel,
												ObjectProvider<ObservationRegistry> observations) {
		return ChatClient.builder(chatModel, observations.getIfUnique(() -> ObservationRegistry.NOOP), null, null);
	}
}
