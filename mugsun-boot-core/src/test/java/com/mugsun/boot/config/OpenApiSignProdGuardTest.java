package com.mugsun.boot.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 非 dev/test/local 下 {@link OpenApiSignProdGuard#KEY}=false（危险配置）拒绝启动；白名单 profile、保持 true 放行；
 * 显式豁免放行并打 WARN。
 */
class OpenApiSignProdGuardTest {

	private static MockEnvironment env(String profiles, String value) {
		MockEnvironment env = new MockEnvironment();
		if (!profiles.isEmpty()) {
			env.setActiveProfiles(profiles.split(","));
		}
		if (value != null) {
			env.setProperty(OpenApiSignProdGuard.KEY, value);
		}
		return env;
	}

	@ParameterizedTest
	@CsvSource({"'',false", "staging,false", "'dev,prod',false", "prod,false", "'prod,demo',false", "PROD,FALSE", "production,' false '"})
	void refusesOutsideDevWhenDisabled(String profiles, String value) {
		assertThatThrownBy(() -> new OpenApiSignProdGuard(env(profiles, value)).run())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(OpenApiSignProdGuard.KEY)
			.hasMessageContaining(OpenApiSignProdGuard.EXEMPT_KEY);
	}

	@ParameterizedTest
	@CsvSource({"dev,false", "test,false", "local,false", "'',true", "prod,true", "'prod,demo',"})
	void startsInDevOrWhenEnabled(String profiles, String value) {
		assertThatCode(() -> new OpenApiSignProdGuard(env(profiles, value)).run()).doesNotThrowAnyException();
	}

	@Test
	void refusesWhenOnlyDefaultProfileIsProd() {
		MockEnvironment e = env("", "false");
		e.setDefaultProfiles("prod");
		assertThatThrownBy(() -> new OpenApiSignProdGuard(e).run()).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void exemptionStartsAndWarns() {
		Logger logger = (Logger) LoggerFactory.getLogger(OpenApiSignProdGuard.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			MockEnvironment e = env("prod", "false");
			e.setProperty(OpenApiSignProdGuard.EXEMPT_KEY, "true");
			assertThatCode(() -> new OpenApiSignProdGuard(e).run()).doesNotThrowAnyException();
			assertThat(appender.list).anySatisfy(ev -> {
				assertThat(ev.getLevel()).isEqualTo(Level.WARN);
				assertThat(ev.getFormattedMessage()).contains(OpenApiSignProdGuard.EXEMPT_KEY);
			});
		} finally {
			logger.detachAppender(appender);
		}
	}

	@Test
	void exemptionFalseStillRefuses() {
		MockEnvironment e = env("prod", "false");
		e.setProperty(OpenApiSignProdGuard.EXEMPT_KEY, "false");
		assertThatThrownBy(() -> new OpenApiSignProdGuard(e).run()).isInstanceOf(IllegalStateException.class);
	}
}
