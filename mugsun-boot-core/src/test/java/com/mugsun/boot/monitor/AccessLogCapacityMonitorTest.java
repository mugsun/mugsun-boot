package com.mugsun.boot.monitor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AccessLogCapacityMonitorTest {

	@Test
	void parsesSpacesCaseAndShortUnits() {
		assertThat(AccessLogCapacityMonitor.parseSize("20 GB")).isEqualTo(20L << 30);
		assertThat(AccessLogCapacityMonitor.parseSize(" 10gb ")).isEqualTo(10L << 30);
		assertThat(AccessLogCapacityMonitor.parseSize("10G")).isEqualTo(10L << 30);
		assertThat(AccessLogCapacityMonitor.parseSize("10 g")).isEqualTo(10L << 30);
		assertThat(AccessLogCapacityMonitor.parseSize("512M")).isEqualTo(512L << 20);
		assertThat(AccessLogCapacityMonitor.parseSize("1T")).isEqualTo(1L << 40);
		assertThat(AccessLogCapacityMonitor.parseSize("64K")).isEqualTo(64L << 10);
		assertThat(AccessLogCapacityMonitor.parseSize("-1")).isEqualTo(-1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "abc", "10 XB", "GB", "1.5GB", "10GG"})
	void invalidThresholdFallsBackToDefault(String value, @TempDir Path dir) {
		assertThat(AccessLogCapacityMonitor.parseOrDefault(value)).isEqualTo(AccessLogCapacityMonitor.DEFAULT_MIN_FREE);
		new AccessLogCapacityMonitor(dir.toString(), value).check();
	}

	@Test
	void alertsOnlyOnStateChange(@TempDir Path dir) {
		AccessLogCapacityMonitor low = new AccessLogCapacityMonitor(dir.toString(), "1000000T");
		low.check();
		assertThat(low.state()).isEqualTo(AccessLogCapacityMonitor.State.LOW);
		low.check();
		assertThat(low.state()).isEqualTo(AccessLogCapacityMonitor.State.LOW);
		AccessLogCapacityMonitor ok = new AccessLogCapacityMonitor(dir.toString(), "1K");
		ok.check();
		assertThat(ok.state()).isEqualTo(AccessLogCapacityMonitor.State.OK);
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "0GB"})
	void nonPositiveThresholdDisablesAlert(String value, @TempDir Path dir) {
		AccessLogCapacityMonitor m = new AccessLogCapacityMonitor(dir.toString(), value);
		m.check();
		assertThat(m.state()).isEqualTo(AccessLogCapacityMonitor.State.OK);
	}

	@Test
	void freeBytesFallsBackToExistingParent(@TempDir Path dir) {
		assertThat(AccessLogCapacityMonitor.freeBytes(dir)).isPositive();
		assertThat(AccessLogCapacityMonitor.freeBytes(dir.resolve("missing/sub"))).isPositive();
	}

	@Test
	void unreadableFreeSpaceWarnsExactlyOnce(@TempDir Path dir) {
		Logger logger = (Logger) LoggerFactory.getLogger(AccessLogCapacityMonitor.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			AccessLogCapacityMonitor m = new AccessLogCapacityMonitor(dir.toString(), "10GB", p -> -1L);
			m.check();
			m.check();
			m.check();
			assertThat(m.state()).isEqualTo(AccessLogCapacityMonitor.State.UNKNOWN);
			assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN)
				.hasSize(1)
				.allSatisfy(e -> assertThat(e.getFormattedMessage()).contains("无法读取"));
		} finally {
			logger.detachAppender(appender);
		}
	}
}
