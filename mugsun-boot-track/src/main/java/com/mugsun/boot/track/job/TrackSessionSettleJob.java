package com.mugsun.boot.track.job;

import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.TrackDS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 会话结算任务：扫 {@code idx_session_settle} 部分索引（settled=0），把静默超 30 分钟的会话定稿落账。
 * <p>定稿语义：is_bounce = (pageviews&lt;=1)（单 PV 跳出，§6 口径）；duration_ms = end_time−start_time 终值；
 * settled 置 1（部分索引自动摘出，后续不再扫描）。单语句 set-based UPDATE，命中部分索引、有界轻量。
 * <p>静默阈值取 Java 侧 UTC 墙钟 {@link java.time.LocalDateTime} 绑定（与 TrackEventStore
 * 「TIMESTAMP=UTC 墙钟」写入规约同口径，不依赖库会话时区）。
 */
@Component
@TrackDS
public class TrackSessionSettleJob {

	private static final Logger log = LoggerFactory.getLogger(TrackSessionSettleJob.class);

	/** 静默超时定稿：end_time 早于阈值（Java 侧 UTC 墙钟，与写入端 LocalDateTime 绑定规约同口径，
	 *  不依赖库会话时区）且未结算 → 定稿跳出/时长并置 settled=1 */
	private static final String SETTLE_SQL = "UPDATE track_session SET"
		+ " is_bounce = CASE WHEN pageviews <= 1 THEN 1 ELSE 0 END,"
		+ " duration_ms = CAST(EXTRACT(EPOCH FROM (end_time - start_time)) * 1000 AS INT),"
		+ " settled = 1, update_time = now()"
		+ " WHERE settled = 0 AND is_deleted = 0 AND end_time < ?";

	private final JdbcTemplate jdbc;
	private final TrackJobGuard guard;

	public TrackSessionSettleJob(DataSource dataSource, TrackJobGuard guard) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.guard = guard;
	}

	/** 固定 tick（会话结算延迟即此周期；UPDATE 走部分索引，空转代价可忽略，不再叠加内存节流） */
	@Scheduled(fixedDelay = TrackConstants.SESSION_SETTLE_TICK_MS)
	public void tick() {
		settleNow();
	}

	/** 供 PowerJob 处理器/集成测试手动触发：立即执行一轮（带分布式锁），返回定稿会话数 */
	public int settleNow() {
		Integer updated = guard.withLock(TrackConstants.LOCK_SESSION_SETTLE, this::doSettle, -1);
		return updated == null ? -1 : updated;
	}

	/** 一轮结算（package-private 供测试直调）；阈值取 Java 侧 UTC 墙钟（与 TrackEventStore 的
	 *  TIMESTAMP=UTC 墙钟 LocalDateTime 绑定规约同口径，杜绝库会话时区漂移） */
	int doSettle() {
		LocalDateTime threshold = LocalDateTime.ofInstant(
			Instant.now().minusMillis(TrackConstants.SESSION_SETTLE_SILENCE_MS), ZoneOffset.UTC);
		int updated = jdbc.update(SETTLE_SQL, threshold);
		if (updated > 0) {
			log.info("会话结算完成：定稿 {} 个静默会话", updated);
		}
		return updated;
	}
}
