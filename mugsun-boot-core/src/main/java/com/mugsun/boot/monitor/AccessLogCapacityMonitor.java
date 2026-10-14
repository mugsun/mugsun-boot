package com.mugsun.boot.monitor;

import com.mugsun.boot.demo.DemoConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.ToLongFunction;

/**
 * 访问日志磁盘余量告警。访问日志留存 ≥180 天是硬要求（《网络安全法》第二十一条），logback 不设 totalSizeCap，
 * 只按天数删除；代价是日志会一直占盘。每小时检查一次日志目录所在磁盘的剩余空间，只在状态变化时打日志：
 * 余量充足→不足打 WARN，不足→恢复打 INFO，取不到余量时打一次 WARN。阈值是 {@code mugsun.demo.access-log.min-free-space}
 * （默认 10GB；写法 10GB / 10 GB / 10G / 512M 都可以），解析失败回落到默认值，阈值 ≤0 时关闭告警并打一次 WARN。
 */
@Component
@ConditionalOnProperty(name = DemoConstants.ENABLED_PROPERTY, havingValue = "true")
public class AccessLogCapacityMonitor {

	static final long DEFAULT_MIN_FREE = DataSize.ofGigabytes(10).toBytes();
	private static final Logger log = LoggerFactory.getLogger(AccessLogCapacityMonitor.class);

	enum State { OK, LOW, UNKNOWN }

	private final Path dir;
	private final long minFree;
	private final ToLongFunction<Path> freeSpace;
	private State state = State.OK;

	public AccessLogCapacityMonitor(
		@Value("${mugsun.demo.access-log.path:${logging.file.path:./logs}}") String dir,
		@Value("${mugsun.demo.access-log.min-free-space:10GB}") String minFree) {
		this(dir, minFree, AccessLogCapacityMonitor::freeBytes);
	}

	/** 测试用：可注入取余量的函数（返回 -1 表示读不到）。 */
	AccessLogCapacityMonitor(String dir, String minFree, ToLongFunction<Path> freeSpace) {
		this.freeSpace = freeSpace;
		this.dir = Path.of(dir);
		this.minFree = parseOrDefault(minFree);
		if (this.minFree <= 0) {
			log.warn("[demo] mugsun.demo.access-log.min-free-space={} 不大于 0，访问日志磁盘余量告警已关闭", minFree);
		}
	}

	/** 解析失败回落到 10GB 并打 WARN，不让启动失败。 */
	static long parseOrDefault(String value) {
		try {
			return parseSize(value);
		} catch (RuntimeException e) {
			log.warn("[demo] mugsun.demo.access-log.min-free-space={} 无法解析，改用默认 10GB", value);
			return DEFAULT_MIN_FREE;
		}
	}

	/** 去掉所有空白、不区分大小写；接受 10GB、10 GB、10G、512M、1T、-1 等写法。 */
	static long parseSize(String value) {
		String s = value.replaceAll("\\s+", "").toUpperCase();
		if (s.matches("[+-]?\\d+[KMGT]")) {
			s = s + "B";
		}
		return DataSize.parse(s).toBytes();
	}

	@Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
	public void check() {
		if (minFree <= 0) {
			return;
		}
		long free = freeSpace.applyAsLong(dir);
		State next = free < 0 ? State.UNKNOWN : free < minFree ? State.LOW : State.OK;
		if (next == state) {
			return;
		}
		switch (next) {
			case LOW -> log.warn("[demo] 访问日志所在磁盘剩余 {} MB，低于告警阈值 {} MB：访问日志需留存 ≥180 天，不会按容量删除，"
				+ "磁盘写满后日志与服务都会出错。请扩容日志卷或转移旧归档（阈值：MUGSUN_DEMO_ACCESS_LOG_MIN_FREE_SPACE）",
				free >> 20, minFree >> 20);
			case UNKNOWN -> log.warn("[demo] 无法读取访问日志目录 {} 所在磁盘的剩余空间，磁盘余量告警暂时失效", dir);
			case OK -> log.info("[demo] 访问日志所在磁盘剩余 {} MB，已恢复到告警阈值 {} MB 以上", free >> 20, minFree >> 20);
		}
		state = next;
	}

	State state() {
		return state;
	}

	/** 目录不存在时向上找最近的已存在父目录；取不到返回 -1。 */
	static long freeBytes(Path dir) {
		Path p = dir.toAbsolutePath();
		while (p != null && !Files.exists(p)) {
			p = p.getParent();
		}
		if (p == null) {
			return -1;
		}
		try {
			return Files.getFileStore(p).getUsableSpace();
		} catch (IOException e) {
			return -1;
		}
	}
}
