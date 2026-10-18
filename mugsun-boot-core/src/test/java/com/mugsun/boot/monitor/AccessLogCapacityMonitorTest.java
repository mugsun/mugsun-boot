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

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
		assertThat(AccessLogCapacityMonitor.parseSize("1048576")).isEqualTo(1L << 20);
		assertThat(AccessLogCapacityMonitor.parseSize("100B")).isEqualTo(100);
	}

	@Test
	void parsesDecimals() {
		assertThat(AccessLogCapacityMonitor.parseSize("1.5GB")).isEqualTo(3L << 29);
		assertThat(AccessLogCapacityMonitor.parseSize("1.5G")).isEqualTo(3L << 29);
		assertThat(AccessLogCapacityMonitor.parseSize("1.5 g")).isEqualTo(3L << 29);
		assertThat(AccessLogCapacityMonitor.parseSize("0.5M")).isEqualTo(1L << 19);
		assertThat(AccessLogCapacityMonitor.parseSize("1.0001K")).isEqualTo(1024);
		assertThat(AccessLogCapacityMonitor.parseOrDefault("2.5GB")).isEqualTo(5L << 29);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "abc", "10 XB", "GB", "1.5.5GB", "10GG", ".5G", "1e3G", "1 0GB", "10 G B", "10IB", "   ", "99999999T", "9223372036854775808"})
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

	@Test
	void runtimeExceptionFromFreeSpaceWarnsExactlyOnce(@TempDir Path dir) {
		Logger logger = (Logger) LoggerFactory.getLogger(AccessLogCapacityMonitor.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			AccessLogCapacityMonitor m = new AccessLogCapacityMonitor(dir.toString(), "10GB", p -> {
				throw new SecurityException("denied");
			});
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

	@Test
	void freeBytesSwallowsRuntimeException() {
		Path bad = new Path() {
			private final Path real = Path.of(".");
			@Override public java.nio.file.FileSystem getFileSystem() { return real.getFileSystem(); }
			@Override public boolean isAbsolute() { return false; }
			@Override public Path getRoot() { return null; }
			@Override public Path getFileName() { return this; }
			@Override public Path getParent() { return null; }
			@Override public int getNameCount() { return 1; }
			@Override public Path getName(int index) { return this; }
			@Override public Path subpath(int b, int e) { return this; }
			@Override public boolean startsWith(Path other) { return false; }
			@Override public boolean endsWith(Path other) { return false; }
			@Override public Path normalize() { return this; }
			@Override public Path resolve(Path other) { return other; }
			@Override public Path relativize(Path other) { return other; }
			@Override public java.net.URI toUri() { throw new UnsupportedOperationException(); }
			@Override public Path toAbsolutePath() { throw new SecurityException("denied"); }
			@Override public Path toRealPath(java.nio.file.LinkOption... o) { return this; }
			@Override public java.nio.file.WatchKey register(java.nio.file.WatchService w, java.nio.file.WatchEvent.Kind<?>[] k, java.nio.file.WatchEvent.Modifier... m) { throw new UnsupportedOperationException(); }
			@Override public int compareTo(Path other) { return 0; }
		};
		assertThat(AccessLogCapacityMonitor.freeBytes(bad)).isEqualTo(-1);
	}

	@Test
	void thresholdEdges() {
		// 0.5G = 512 MiB
		assertThat(AccessLogCapacityMonitor.parseSize("0.5G")).isEqualTo(512L << 20);
		// 负小数原样为负 → 调用方关闭告警
		assertThat(AccessLogCapacityMonitor.parseSize("-0.5G")).isEqualTo(-(512L << 20));
		// 不足 1 字节向下取整为 0 → 关闭告警
		assertThat(AccessLogCapacityMonitor.parseSize("0.5B")).isZero();
		assertThat(AccessLogCapacityMonitor.parseSize("0.3")).isZero();
		// GiB/MiB/KiB/TiB 与 G 同义（1024 进位）
		assertThat(AccessLogCapacityMonitor.parseSize("10GiB")).isEqualTo(10L << 30);
		assertThat(AccessLogCapacityMonitor.parseSize("10 gib")).isEqualTo(10L << 30);
		assertThat(AccessLogCapacityMonitor.parseSize("1KiB")).isEqualTo(1024);
		assertThat(AccessLogCapacityMonitor.parseSize("1kb")).isEqualTo(1024);
		assertThat(AccessLogCapacityMonitor.parseSize("1TiB")).isEqualTo(1L << 40);
		// 只去首尾空白：数字与单位之间可有空白
		assertThat(AccessLogCapacityMonitor.parseSize("\t10 GB\n")).isEqualTo(10L << 30);
		// 超出 long → 抛异常，parseOrDefault 回落 10GB
		assertThatThrownBy(() -> AccessLogCapacityMonitor.parseSize("99999999T")).isInstanceOf(ArithmeticException.class);
		assertThat(AccessLogCapacityMonitor.parseOrDefault("99999999T")).isEqualTo(AccessLogCapacityMonitor.DEFAULT_MIN_FREE);
	}

	@ParameterizedTest
	@ValueSource(strings = {"-0.5G", "0.5B", "0.3"})
	void nonPositiveAfterRoundingDisablesAlert(String value, @TempDir Path dir) {
		AccessLogCapacityMonitor m = new AccessLogCapacityMonitor(dir.toString(), value, p -> 0L);
		m.check();
		assertThat(m.state()).isEqualTo(AccessLogCapacityMonitor.State.OK);
	}

	@Test
	void unknownRecoversToOkAndToLow(@TempDir Path dir) {
		AtomicLong free = new AtomicLong(-1);
		AccessLogCapacityMonitor m = new AccessLogCapacityMonitor(dir.toString(), "1K", p -> free.get());
		m.check();
		assertThat(m.state()).isEqualTo(AccessLogCapacityMonitor.State.UNKNOWN);
		free.set(4096);
		m.check();
		assertThat(m.state()).isEqualTo(AccessLogCapacityMonitor.State.OK);
		free.set(-1);
		m.check();
		assertThat(m.state()).isEqualTo(AccessLogCapacityMonitor.State.UNKNOWN);
		free.set(10);
		m.check();
		assertThat(m.state()).isEqualTo(AccessLogCapacityMonitor.State.LOW);
	}

	@Test
	void unknownWarnIncludesCause(@TempDir Path dir) {
		Logger logger = (Logger) LoggerFactory.getLogger(AccessLogCapacityMonitor.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			AccessLogCapacityMonitor m = new AccessLogCapacityMonitor(dir.toString(), "10GB", p -> {
				throw new java.io.UncheckedIOException(new IOException("disk gone"));
			});
			m.check();
			assertThat(appender.list).filteredOn(e -> e.getLevel() == Level.WARN).hasSize(1)
				.allSatisfy(e -> assertThat(e.getFormattedMessage()).contains("java.io.IOException: disk gone"));
		} finally {
			logger.detachAppender(appender);
		}
	}

	@Test
	void freeBytesReturnsMinusOneOnIOException(@TempDir Path dir) {
		assertThat(AccessLogCapacityMonitor.freeBytes(dir, p -> {
			throw new IOException("boom");
		})).isEqualTo(-1);
	}

	@Test
	void errorStillPropagates(@TempDir Path dir) {
		assertThatThrownBy(() -> AccessLogCapacityMonitor.freeBytes(dir, p -> {
			throw new StackOverflowError("err");
		})).isInstanceOf(StackOverflowError.class);
		AccessLogCapacityMonitor m = new AccessLogCapacityMonitor(dir.toString(), "10GB", p -> {
			throw new AssertionError("err");
		});
		assertThatThrownBy(m::check).isInstanceOf(AssertionError.class);
	}
}
