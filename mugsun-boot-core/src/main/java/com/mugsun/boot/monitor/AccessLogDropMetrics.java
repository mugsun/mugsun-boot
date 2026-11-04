package com.mugsun.boot.monitor;

import com.mugsun.boot.demo.DemoConstants;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 把 {@link AccessLogDropStats} 注册为 Micrometer 指标 {@value #METRIC}
 * （demo 开启时；#79）。只读共享计数，不在请求路径上访问本 bean。
 */
@Component
@ConditionalOnProperty(name = DemoConstants.ENABLED_PROPERTY, havingValue = "true")
public class AccessLogDropMetrics {

	static final String METRIC = "mugsun.demo.access_log.dropped";

	public AccessLogDropMetrics(MeterRegistry registry) {
		FunctionCounter.builder(METRIC, AccessLogDropStats.DROPPED, AtomicLong::doubleValue)
			.description("Access-log events dropped when the async queue was full after offer timeout")
			.register(registry);
	}
}
