package com.mugsun.boot.monitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AccessLogCapacityMonitorTest {

	@Test
	void sumsOnlyAccessFilesAndWarnsAt80Percent(@TempDir Path dir) throws Exception {
		Files.write(dir.resolve("mugsun-boot-access.log"), new byte[600]);
		Files.write(dir.resolve("mugsun-boot-access.2026-10-13.0.log.gz"), new byte[300]);
		Files.write(dir.resolve("mugsun-boot.log"), new byte[5000]);
		long used = AccessLogCapacityMonitor.usedBytes(dir);
		assertThat(used).isEqualTo(900);
		assertThat(AccessLogCapacityMonitor.overThreshold(used, 1000)).isTrue();
		assertThat(AccessLogCapacityMonitor.overThreshold(used, 2000)).isFalse();
		assertThat(AccessLogCapacityMonitor.usedBytes(dir.resolve("missing"))).isZero();
	}
}
