package com.mugsun.boot.monitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AccessLogCapacityMonitorTest {

	@Test
	void parsesSizeWithSpacesAndLowerCase() {
		assertThat(AccessLogCapacityMonitor.parseSize("20 GB")).isEqualTo(20L << 30);
		assertThat(AccessLogCapacityMonitor.parseSize(" 10gb ")).isEqualTo(10L << 30);
		assertThat(AccessLogCapacityMonitor.parseSize("512 MB")).isEqualTo(512L << 20);
		assertThat(AccessLogCapacityMonitor.parseSize("10GB")).isEqualTo(10L << 30);
	}

	@Test
	void constructorAcceptsSpacedSize(@TempDir Path dir) {
		new AccessLogCapacityMonitor(dir.toString(), "20 GB").check();
	}

	@Test
	void warnsOnlyWhenFreeBelowThreshold() {
		assertThat(AccessLogCapacityMonitor.belowThreshold(5, 10)).isTrue();
		assertThat(AccessLogCapacityMonitor.belowThreshold(10, 10)).isFalse();
		assertThat(AccessLogCapacityMonitor.belowThreshold(-1, 10)).isFalse();
		assertThat(AccessLogCapacityMonitor.belowThreshold(5, 0)).isFalse();
	}

	@Test
	void freeBytesFallsBackToExistingParent(@TempDir Path dir) {
		assertThat(AccessLogCapacityMonitor.freeBytes(dir)).isPositive();
		assertThat(AccessLogCapacityMonitor.freeBytes(dir.resolve("missing/sub"))).isPositive();
	}
}
