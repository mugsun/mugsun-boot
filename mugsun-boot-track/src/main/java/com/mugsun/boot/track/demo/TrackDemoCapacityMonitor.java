package com.mugsun.boot.track.demo;

import com.mugsun.boot.demo.DemoCapacityGate;
import com.mugsun.boot.demo.DemoConstants;
import com.mugsun.boot.track.TrackDS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 只读演示模式的埋点库容量巡检（安全复审 F3）：每 5 分钟查一次埋点库大小（只读，{@code pg_database_size}），
 * 超过告警线打 WARN，超过硬上限打 ERROR 并经 {@link DemoCapacityGate} 让采集接口返回 503，回落到上限以下自动恢复。
 */
@Component
@TrackDS
@ConditionalOnProperty(name = DemoConstants.ENABLED_PROPERTY, havingValue = "true")
public class TrackDemoCapacityMonitor {

	private static final Logger log = LoggerFactory.getLogger(TrackDemoCapacityMonitor.class);
	private static final long MB = 1024L * 1024L;

	private final JdbcTemplate jdbc;
	private final DemoCapacityGate gate;
	private final long warnBytes;
	private final long maxBytes;

	public TrackDemoCapacityMonitor(DataSource dataSource, DemoCapacityGate gate,
									@Value("${mugsun.demo.track-capacity.warn-mb:2048}") long warnMb,
									@Value("${mugsun.demo.track-capacity.max-mb:4096}") long maxMb) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.gate = gate;
		this.warnBytes = warnMb * MB;
		this.maxBytes = maxMb * MB;
	}

	@Scheduled(initialDelay = 10_000, fixedDelay = 300_000)
	public void check() {
		Long bytes;
		try {
			bytes = jdbc.queryForObject("SELECT pg_database_size(current_database())", Long.class);
		} catch (RuntimeException e) {
			log.warn("[demo] 埋点库容量巡检失败：{}", e.getMessage());
			return;
		}
		evaluate(bytes == null ? 0L : bytes);
	}

	void evaluate(long bytes) {
		boolean full = maxBytes > 0 && bytes >= maxBytes;
		boolean wasFull = gate.trackFull();
		gate.updateTrack(bytes, full);
		if (full) {
			log.error("[demo] 埋点库 {} MB 已超过上限 {} MB，暂停匿名采集（每日重置后恢复）", bytes / MB, maxBytes / MB);
		} else if (warnBytes > 0 && bytes >= warnBytes) {
			log.warn("[demo] 埋点库 {} MB 已超过告警线 {} MB", bytes / MB, warnBytes / MB);
		} else if (wasFull) {
			log.info("[demo] 埋点库 {} MB 已回落到上限以下，恢复采集", bytes / MB);
		}
	}
}
