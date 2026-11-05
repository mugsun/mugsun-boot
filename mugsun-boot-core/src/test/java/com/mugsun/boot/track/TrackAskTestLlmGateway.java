package com.mugsun.boot.track;

import com.mugsun.boot.common.module.TrackAskIntent;
import com.mugsun.boot.common.module.TrackAskLlmGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 集成测试用可控问数据 LLM 网关：默认 off（走规则），可切 throw/unsupported。
 * <p>lite 模式（不加 {@code @Configuration}/{@code @TestConfiguration}），经基座 {@code @Import}
 * 挂进共享上下文——子类再 {@code @Import} 会改缓存 key、另起上下文，撞 warm-flow 双 Bean 并污染 Redis。
 */
public class TrackAskTestLlmGateway {

	/** off=规则优先；throw=模拟超时；unsupported=模型明确不支持 */
	public static final AtomicReference<String> MODE = new AtomicReference<>("off");

	public static void reset() {
		MODE.set("off");
	}

	@Bean
	@Primary
	TrackAskLlmGateway trackAskTestLlmGateway() {
		return new Controllable();
	}

	static final class Controllable implements TrackAskLlmGateway {
		@Override
		public TrackAskIntent extract(String question, Integer daysHint) {
			return switch (MODE.get()) {
				case "throw" -> throw new RuntimeException("mock llm timeout");
				case "unsupported" -> TrackAskIntent.unsupported("mock unsupported");
				default -> TrackAskIntent.unsupported("llm-off"); // 让服务走规则
			};
		}
	}
}
