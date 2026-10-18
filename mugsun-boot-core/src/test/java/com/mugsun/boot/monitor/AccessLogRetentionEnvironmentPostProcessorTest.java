package com.mugsun.boot.monitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class AccessLogRetentionEnvironmentPostProcessorTest {

	private final AccessLogRetentionEnvironmentPostProcessor epp = new AccessLogRetentionEnvironmentPostProcessor(new DeferredLogs());

	private String apply(String raw) {
		MockEnvironment env = new MockEnvironment().withProperty(AccessLogRetentionEnvironmentPostProcessor.PROPERTY, raw);
		epp.postProcessEnvironment(env, null);
		return env.getProperty(AccessLogRetentionEnvironmentPostProcessor.PROPERTY);
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-5", "90", "179", "179.9", "abc", "", "200days"})
	void belowMinimumOrInvalidBecomes180WithWarn(String raw) {
		assertThat(apply(raw)).isEqualTo("180");
		assertThat(AccessLogRetentionEnvironmentPostProcessor.evaluate(raw).warn()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"180", "200", " 365 ", "100000"})
	void integerAtOrAboveMinimumUnchanged(String raw) {
		assertThat(apply(raw)).isEqualTo(raw);
		assertThat(AccessLogRetentionEnvironmentPostProcessor.evaluate(raw)).isNull();
	}

	@ParameterizedTest
	@CsvSource({"200.5,200", "180.0,180", "180.99,180", "365.7,365", "99999999999,2147483647"})
	void decimalOrHugeAtOrAboveMinimumRoundsDownWithoutWarn(String raw, String expected) {
		assertThat(apply(raw)).isEqualTo(expected);
		assertThat(AccessLogRetentionEnvironmentPostProcessor.evaluate(raw).warn()).isFalse();
	}

	@Test
	void unsetLeavesLogbackDefault() {
		MockEnvironment env = new MockEnvironment();
		epp.postProcessEnvironment(env, null);
		assertThat(env.getProperty(AccessLogRetentionEnvironmentPostProcessor.PROPERTY)).isNull();
	}
}
