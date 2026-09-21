package com.mugsun.boot.track.job;

import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.TrackDS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 单应用明细保留清理任务（G105）：按 {@code track_app.retention_days} 逐应用到期线删除
 * track_event / track_event_data（同 received_at 月分区明细，两张表同口径清理）。
 * <p><b>双层语义</b>：分区为全应用共享的月度物理单元，{@link TrackPartitionJob} 按
 * {@code max(retention_days)} 整分区 DETACH→DROP <b>粗清</b>；本任务按单应用 retention_days 行级 <b>细清</b>，
 * 承接「单应用更短保留期」的差异（TrackPartitionJob 头部的 L2 承接位，G105 兑现）。两层并存互不替代。
 * <p><b>到期线</b>：received_at 早于 now − retention_days（LEFT JOIN track_app 逐行按应用判定；
 * 应用缺失/已删回退 {@value TrackConstants#DEFAULT_RETENTION_DAYS} 天）。
 * 到期线取 Java 侧 UTC 墙钟绑定（与 TrackEventStore「TIMESTAMP=UTC 墙钟」写入规约同口径，不依赖库会话时区）。
 * <p><b>删除方式</b>：{@code DELETE ... WHERE ctid IN (SELECT ... ORDER BY received_at LIMIT ?)} 分批
 * （{@value TrackConstants#EVENT_CLEAN_BATCH_SIZE} 行/批 × 最多 {@value TrackConstants#EVENT_CLEAN_MAX_BATCHES}
 * 批/表/轮），ctid 分批防长事务；循环至无到期行或批数封顶，剩余逐轮消化。
 * <p><b>节奏</b>：每小时 tick + 内存节流每日一轮（多节点各自节流 + 分布式锁兜底，同回放/响应体清理范式）。
 * 明细删除天然幂等可重入（无外部副作用）。
 */
@Component
@TrackDS
public class TrackEventCleanJob {

	private static final Logger log = LoggerFactory.getLogger(TrackEventCleanJob.class);

	/** 事件主表分批删除：逐应用保留期到期线（应用缺失回退默认），ctid 分批防长事务 */
	private static final String DELETE_EXPIRED_EVENT = "DELETE FROM track_event WHERE ctid IN"
		+ " (SELECT e.ctid FROM track_event e"
		+ " LEFT JOIN track_app a ON a.app_key = e.app_key AND a.is_deleted = 0"
		+ " WHERE e.received_at < ? - make_interval(days => coalesce(a.retention_days, ?))"
		+ " ORDER BY e.received_at LIMIT ?)";

	/** EAV 明细表分批删除（同 received_at 月分区，与主表同口径） */
	private static final String DELETE_EXPIRED_EVENT_DATA = "DELETE FROM track_event_data WHERE ctid IN"
		+ " (SELECT e.ctid FROM track_event_data e"
		+ " LEFT JOIN track_app a ON a.app_key = e.app_key AND a.is_deleted = 0"
		+ " WHERE e.received_at < ? - make_interval(days => coalesce(a.retention_days, ?))"
		+ " ORDER BY e.received_at LIMIT ?)";

	private final JdbcTemplate jdbc;
	private final TrackJobGuard guard;
	/** 上次清理日期（UTC；内存节流每日一轮，多节点各自节流 + 分布式锁兜底） */
	private volatile LocalDate lastCleanDay;

	public TrackEventCleanJob(DataSource dataSource, TrackJobGuard guard) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.guard = guard;
	}

	/** 每小时 tick：每日一轮清理（到期判定按行级保留期，幂等） */
	@Scheduled(fixedDelay = TrackConstants.EVENT_CLEAN_TICK_MS)
	public void tick() {
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		if (lastCleanDay != null && !today.isAfter(lastCleanDay)) {
			return;
		}
		cleanNow();
	}

	/** 供 PowerJob 处理器/集成测试手动触发：立即执行一轮（不经节流；带分布式锁），返回执行摘要 */
	public String cleanNow() {
		return guard.withLock(TrackConstants.LOCK_EVENT_CLEAN, this::doClean, "未获调度锁（他节点执行中），跳过本轮");
	}

	/** 一轮清理（package-private 供测试直调）：两表各自分批循环至无到期行或触批数上限 */
	String doClean() {
		int events = cleanTable(DELETE_EXPIRED_EVENT);
		int eventData = cleanTable(DELETE_EXPIRED_EVENT_DATA);
		lastCleanDay = LocalDate.now(ZoneOffset.UTC);
		String summary = "事件明细保留清理完成：track_event 删除 " + events + " 行，track_event_data 删除 " + eventData + " 行";
		if (events > 0 || eventData > 0) {
			log.info(summary);
		}
		return summary;
	}

	/** 单表分批循环删除：每批 {@value TrackConstants#EVENT_CLEAN_BATCH_SIZE} 行，至无到期行或批数封顶 */
	private int cleanTable(String deleteSql) {
		int total = 0;
		for (int batch = 0; batch < TrackConstants.EVENT_CLEAN_MAX_BATCHES; batch++) {
			int deleted = jdbc.update(deleteSql, OffsetDateTime.now(ZoneOffset.UTC),
				TrackConstants.DEFAULT_RETENTION_DAYS, TrackConstants.EVENT_CLEAN_BATCH_SIZE);
			total += deleted;
			if (deleted < TrackConstants.EVENT_CLEAN_BATCH_SIZE) {
				break;
			}
		}
		return total;
	}
}
