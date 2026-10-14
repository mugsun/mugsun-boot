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

/**
 * 访问日志磁盘余量告警。访问日志留存 ≥180 天是硬要求（《网络安全法》第二十一条），logback 不设 totalSizeCap，
 * 只按天数删除；代价是日志会一直占盘。每小时检查一次日志目录所在磁盘的剩余空间，低于
 * {@code mugsun.demo.access-log.min-free-space}（默认 10GB）打 WARN，提示扩容或接入外部留存。
 */
@Component
@ConditionalOnProperty(name = DemoConstants.ENABLED_PROPERTY, havingValue = "true")
public class AccessLogCapacityMonitor {

	private static final Logger log = LoggerFactory.getLogger(AccessLogCapacityMonitor.class);

	private final Path dir;
	private final long minFree;

	public AccessLogCapacityMonitor(
		@Value("${mugsun.demo.access-log.path:${logging.file.path:./logs}}") String dir,
		@Value("${mugsun.demo.access-log.min-free-space:10GB}") String minFree) {
		this.dir = Path.of(dir);
		this.minFree = parseSize(minFree);
	}

	/** 容量配置去掉所有空白再解析，"10 GB"、" 10gb " 都按 10GB 处理。 */
	static long parseSize(String value) {
		return DataSize.parse(value.replaceAll("\\s+", "").toUpperCase()).toBytes();
	}

	@Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
	public void check() {
		long free = freeBytes(dir);
		if (belowThreshold(free, minFree)) {
			log.warn("[demo] 访问日志所在磁盘剩余 {} MB，低于告警阈值 {} MB：访问日志需留存 ≥180 天，不会自动按容量删除，"
				+ "磁盘写满后日志与服务都会出错。请扩容日志卷或接入外部日志留存（阈值：MUGSUN_DEMO_ACCESS_LOG_MIN_FREE_SPACE）",
				free >> 20, minFree >> 20);
		}
	}

	static boolean belowThreshold(long free, long minFree) {
		return free >= 0 && minFree > 0 && free < minFree;
	}

	/** 目录不存在时向上找最近的已存在父目录；取不到返回 -1（不告警）。 */
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
