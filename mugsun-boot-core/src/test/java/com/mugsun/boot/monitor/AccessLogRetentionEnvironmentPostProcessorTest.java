package com.mugsun.boot.monitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class AccessLogRetentionEnvironmentPostProcessorTest {

	private final AccessLogRetentionEnvironmentPostProcessor epp = new AccessLogRetentionEnvironmentPostProcessor(new DeferredLogs());

	@ParameterizedTest
	@ValueSource(strings = {"0", "-5", "90", "179", "abc", ""})
	void belowMinimumOrInvalidBecomes180(String raw) {
		MockEnvironment env = new MockEnvironment().withProperty(AccessLogRetentionEnvironmentPostProcessor.PROPERTY, raw);
		epp.postProcessEnvironment(env, null);
		assertThat(env.getProperty(AccessLogRetentionEnvironmentPostProcessor.PROPERTY)).isEqualTo("180");
	}

	@ParameterizedTest
	@ValueSource(strings = {"180", "200", " 365 "})
	void atOrAboveMinimumUnchanged(String raw) {
		MockEnvironment env = new MockEnvironment().withProperty(AccessLogRetentionEnvironmentPostProcessor.PROPERTY, raw);
		epp.postProcessEnvironment(env, null);
		assertThat(env.getProperty(AccessLogRetentionEnvironmentPostProcessor.PROPERTY)).isEqualTo(raw);
		assertThat(env.getPropertySources().contains(AccessLogRetentionEnvironmentPostProcessor.SOURCE)).isFalse();
	}

	@Test
	void unsetLeavesLogbackDefault() {
		MockEnvironment env = new MockEnvironment();
		epp.postProcessEnvironment(env, null);
		assertThat(env.getProperty(AccessLogRetentionEnvironmentPostProcessor.PROPERTY)).isNull();
	}
}
