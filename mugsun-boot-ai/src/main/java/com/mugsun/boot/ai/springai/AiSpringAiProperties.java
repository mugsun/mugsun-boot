package com.mugsun.boot.ai.springai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Spring AI 接入的配置，前缀 {@code mugsun.ai.spring-ai}。不配也能用。
 */
@Component
@ConfigurationProperties(prefix = "mugsun.ai.spring-ai")
public class AiSpringAiProperties {

	/** 一次模型请求最多等多久：非流式是整次请求，流式是相邻两段数据之间。默认 5 分钟 */
	private Duration timeout = Duration.ofMinutes(5);

	public Duration getTimeout() {
		return timeout;
	}

	public void setTimeout(Duration timeout) {
		if (timeout == null || timeout.isZero() || timeout.isNegative()) {
			throw new IllegalArgumentException("mugsun.ai.spring-ai.timeout 要大于 0，例如 5m、90s");
		}
		this.timeout = timeout;
	}
}
