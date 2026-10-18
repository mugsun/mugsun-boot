package com.mugsun.boot.monitor;

import com.mugsun.boot.demo.DemoConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.ToLongFunction;

/**
 * 访问日志磁盘余量告警。访问日志留存 ≥180 天是硬要求（《网络安全法》第二十一条），logback 不设 totalSizeCap，
 * 只按天数删除；代价是日志会一直占盘。每小时检查一次日志目录所在磁盘的剩余空间，只在状态变化时打日志：
 * 余量充足→不足打 WARN，不足→恢复打 INFO，取不到余量时打一次 WARN。阈值是 {@code mugsun.demo.access-log.min-free-space}
 * （默认 10GB；单位写法见 {@link #parseSize}），解析失败回落到默认值，阈值 ≤0 时关闭告警并打一次 WARN。
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

	@Autowired
	public AccessLogCapacityMonitor(
		@Value("${mugsun.demo.access-log.path:${logging.file.path:./logs}}") String dir,
		@Value("${mugsun.demo.access-log.min-free-space:10GB}") String minFree) {
		this(dir, minFree, p -> {
			try {
				return usableSpace(p, DEFAULT_LOOKUP);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
	}

	/** 测试用：可注入取余量的函数（返回 -1 或抛 RuntimeException 表示读不到；Error 不拦截）。 */
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

	private static final Pattern SIZE = Pattern.compile("([+-]?\\d+(?:\\.\\d+)?)\\s*(?:([KMGT])(?:IB|B)?|B)?");

	/**
	 * 容量解析，不区分大小写，只去掉首尾空白；数字与单位之间可有空白（10 GB），数字内部不行（1 0GB 无效）。
	 * <ul>
	 * <li>单位：无单位或 B = 字节；K/KB/KiB、M/MB/MiB、G/GB/GiB、T/TB/TiB，一律按 1024 进位。</li>
	 * <li>小数：允许（1.5G、0.5G），乘出来向下取整到整字节；不足 1 字节（如 0.5B、0.3）得 0。</li>
	 * <li>负数（含负小数，如 -0.5G）原样返回负值；结果 ≤0 由调用方视为关闭告警。</li>
	 * <li>无法解析（格式不对、指数写法、超出 long 范围）抛 IllegalArgumentException / ArithmeticException。</li>
	 * </ul>
	 */
	static long parseSize(String value) {
		String s = value.strip().toUpperCase();
		Matcher m = SIZE.matcher(s);
		if (!m.matches()) {
			throw new IllegalArgumentException("无法解析的容量：" + value);
		}
		int shift = switch (m.group(2) == null ? "" : m.group(2)) {
			case "K" -> 10;
			case "M" -> 20;
			case "G" -> 30;
			case "T" -> 40;
			default -> 0;
		};
		return new BigDecimal(m.group(1)).multiply(BigDecimal.valueOf(1L << shift))
			.setScale(0, RoundingMode.FLOOR).longValueExact();
	}

	@Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
	public void check() {
		if (minFree <= 0) {
			return;
		}
		long free;
		String cause = "目录及其上级目录都不存在";
		try {
			free = freeSpace.applyAsLong(dir);
		} catch (RuntimeException e) {
			free = -1;
			cause = (e instanceof UncheckedIOException u ? u.getCause() : e).toString();
		}
		State next = free < 0 ? State.UNKNOWN : free < minFree ? State.LOW : State.OK;
		if (next == state) {
			return;
		}
		switch (next) {
			case LOW -> log.warn("[demo] 访问日志所在磁盘剩余 {} MB，低于告警阈值 {} MB：访问日志需留存 ≥180 天，不会按容量删除，"
				+ "磁盘写满后日志与服务都会出错。请扩容日志卷或转移旧归档（阈值：MUGSUN_DEMO_ACCESS_LOG_MIN_FREE_SPACE）",
				free >> 20, minFree >> 20);
			case UNKNOWN -> log.warn("[demo] 无法读取访问日志目录 {} 所在磁盘的剩余空间，磁盘余量告警暂时失效：{}", dir, cause);
			case OK -> log.info("[demo] 访问日志所在磁盘剩余 {} MB，已恢复到告警阈值 {} MB 以上", free >> 20, minFree >> 20);
		}
		state = next;
	}

	State state() {
		return state;
	}

	/** 取某个已存在路径所在磁盘的可用空间。 */
	@FunctionalInterface
	interface StoreLookup {
		long usable(Path existing) throws IOException;
	}

	static final StoreLookup DEFAULT_LOOKUP = p -> Files.getFileStore(p).getUsableSpace();

	/** 目录不存在时向上找最近的已存在父目录；都不存在返回 -1；IOException / RuntimeException 原样抛出。 */
	static long usableSpace(Path dir, StoreLookup lookup) throws IOException {
		Path p = dir.toAbsolutePath();
		while (p != null && !Files.exists(p)) {
			p = p.getParent();
		}
		return p == null ? -1 : lookup.usable(p);
	}

	/** 同 {@link #usableSpace}，但 IOException 与 RuntimeException 都返回 -1（Error 照常抛出）。 */
	static long freeBytes(Path dir, StoreLookup lookup) {
		try {
			return usableSpace(dir, lookup);
		} catch (IOException | RuntimeException e) {
			return -1;
		}
	}

	static long freeBytes(Path dir) {
		return freeBytes(dir, DEFAULT_LOOKUP);
	}
}
