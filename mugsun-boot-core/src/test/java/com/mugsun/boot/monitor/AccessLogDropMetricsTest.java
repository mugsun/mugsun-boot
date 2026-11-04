package com.mugsun.boot.monitor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #79：demo 下 {@link AccessLogDropMetrics} 把共享丢弃计数注册为
 * {@code mugsun.demo.access_log.dropped}。
 */
class AccessLogDropMetricsTest {

	@AfterEach
	void tearDown() {
		AccessLogDropStats.resetForTest();
	}

	@Test
	void registersFunctionCounterBoundToSharedDropped() {
		AccessLogDropStats.resetForTest();
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		new AccessLogDropMetrics(registry);

		assertThat(registry.find(AccessLogDropMetrics.METRIC).functionCounter())
			.isNotNull();
		assertThat(registry.find(AccessLogDropMetrics.METRIC).functionCounter().count())
			.isZero();

		AccessLogDropStats.DROPPED.addAndGet(3);
		assertThat(registry.find(AccessLogDropMetrics.METRIC).functionCounter().count())
			.isEqualTo(3.0);
	}
}
