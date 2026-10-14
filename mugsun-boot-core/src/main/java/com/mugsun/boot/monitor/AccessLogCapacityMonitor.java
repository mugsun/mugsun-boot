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
import java.util.stream.Stream;

/**
 * 访问日志容量告警：logback 达到 totalSizeCap 时会删最早的归档，哪怕还不满 180 天（刷量可以触发）。
 * 每小时统计一次 {@code *-access*} 文件总量，超过上限的 {@value #WARN_PERCENT}% 打 WARN，提示扩容或外部留存。
 */
@Component
@ConditionalOnProperty(name = DemoConstants.ENABLED_PROPERTY, havingValue = "true")
public class AccessLogCapacityMonitor {

	static final int WARN_PERCENT = 80;
	private static final Logger log = LoggerFactory.getLogger(AccessLogCapacityMonitor.class);

	private final Path dir;
	private final long cap;

	public AccessLogCapacityMonitor(
		@Value("${mugsun.demo.access-log.path:${logging.file.path:./logs}}") String dir,
		@Value("${mugsun.demo.access-log.total-size-cap:20GB}") String cap) {
		this.dir = Path.of(dir);
		this.cap = DataSize.parse(cap.trim().toUpperCase()).toBytes();
	}

	@Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
	public void check() {
		long used = usedBytes(dir);
		if (overThreshold(used, cap)) {
			log.warn("[demo] 访问日志已用 {} MB，接近总量上限 {} MB（{}%）：达到上限会删除最早的日志，即使不满 180 天。"
				+ "请调大 MUGSUN_DEMO_ACCESS_LOG_TOTAL_SIZE_CAP、扩容日志卷，或接入外部日志留存",
				used >> 20, cap >> 20, used * 100 / cap);
		}
	}

	static boolean overThreshold(long used, long cap) {
		return cap > 0 && used * 100 >= cap * WARN_PERCENT;
	}

	static long usedBytes(Path dir) {
		if (!Files.isDirectory(dir)) {
			return 0;
		}
		try (Stream<Path> s = Files.list(dir)) {
			return s.filter(p -> p.getFileName().toString().contains("-access")).mapToLong(p -> {
				try {
					return Files.size(p);
				} catch (IOException e) {
					return 0;
				}
			}).sum();
		} catch (IOException e) {
			return 0;
		}
	}
}
