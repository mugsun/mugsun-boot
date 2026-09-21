package com.mugsun.boot.track.job;

import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.TrackDS;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分区生命周期任务：月分区预建 + 到期清理 + 默认分区残留告警（track_event / track_event_data 共用节奏）。
 * <p><b>预建</b>：每日最多一轮幂等确保当月 + 次月分区存在（CREATE TABLE IF NOT EXISTS；
 * 设计触发点为每月 {@value TrackConstants#PARTITION_PREBUILD_DAY_OF_MONTH} 日起预建次月，实现按日幂等确保，
 * 兼容「25 日窗口期服务未运行/当月重启」的漏建场景；事件分区带 {@code WITH (fillfactor=100)} 追加不更新）。
 * <p><b>清理</b>：分区为全应用共享的月度物理单元，无法按应用粒度 DROP——故到期线取
 * {@code max(track_app.retention_days)}（最长保留应用决定分区存活），月分区上界整体早于到期线才
 * DETACH→DROP（默认不归档）；单应用更短保留期的差异由 L2 明细级清理承接（G105 起已由 TrackEventCleanJob 承接）。
 * <p><b>默认分区告警</b>：track_event_default / track_event_data_default 非空 = 预建失败或数据越界，
 * log 告警 + Micrometer 计数（{@value TrackConstants#METRIC_DEFAULT_PARTITION_ROWS}）。
 */
@Component
@TrackDS
public class TrackPartitionJob {

	private static final Logger log = LoggerFactory.getLogger(TrackPartitionJob.class);

	/** 分区名时间格式：track_event_2026_08 */
	private static final DateTimeFormatter PART_MONTH = DateTimeFormatter.ofPattern("yyyy_MM");
	/** 事件主表月分区名（DETACH/DROP 标识符来源 pg_catalog，仍正则校验双保险） */
	private static final Pattern EVENT_PART_NAME = Pattern.compile("^track_event_(\\d{4})_(\\d{2})$");
	/** EAV 表月分区名 */
	private static final Pattern EVENT_DATA_PART_NAME = Pattern.compile("^track_event_data_(\\d{4})_(\\d{2})$");

	private final JdbcTemplate jdbc;
	private final TrackJobGuard guard;
	private final MeterRegistry metrics;
	/** 上次维护日期（UTC；内存节流每日一轮，多节点各自节流 + 分布式锁兜底） */
	private volatile LocalDate lastMaintainDay;

	public TrackPartitionJob(DataSource dataSource, TrackJobGuard guard, MeterRegistry metrics) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.guard = guard;
		this.metrics = metrics;
	}

	/** 每小时 tick：每日一轮维护（预建幂等，清理按到期线） */
	@Scheduled(fixedDelay = TrackConstants.PARTITION_TICK_MS)
	public void tick() {
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		if (lastMaintainDay != null && !today.isAfter(lastMaintainDay)) {
			return;
		}
		maintainNow();
	}

	/** 供 PowerJob 处理器/集成测试手动触发：立即执行一轮（不经节流；带分布式锁），返回执行摘要 */
	public String maintainNow() {
		return guard.withLock(TrackConstants.LOCK_PARTITION, this::doMaintain, "未获调度锁（他节点执行中），跳过本轮");
	}

	/** 一轮维护（package-private 供测试直调）：预建 → 清理 → 默认分区巡检 */
	String doMaintain() {
		YearMonth current = YearMonth.now(ZoneOffset.UTC);
		ensureMonthPartitions(current);
		ensureMonthPartitions(current.plusMonths(1));
		int dropped = dropExpiredPartitions();
		checkDefaultPartitions();
		lastMaintainDay = LocalDate.now(ZoneOffset.UTC);
		String summary = "分区维护完成：确保 " + current + "/" + current.plusMonths(1) + " 分区存在，清理到期分区 " + dropped + " 个";
		log.info(summary);
		return summary;
	}

	/** 确保指定月份的两张月分区存在（幂等；public 供集成测试验证任意月份的预建/清理路径） */
	public void ensureMonthPartitions(YearMonth month) {
		LocalDate monthStart = month.atDay(1);
		LocalDate monthEnd = month.plusMonths(1).atDay(1);
		String suffix = month.format(PART_MONTH);
		// 事件分区：fillfactor=100（追加不更新，PG 不允许父表带存储参数，只能设到叶子分区）
		jdbc.execute("CREATE TABLE IF NOT EXISTS \"track_event_" + suffix + "\" PARTITION OF track_event"
			+ " FOR VALUES FROM ('" + monthStart + "') TO ('" + monthEnd + "') WITH (fillfactor = 100)");
		jdbc.execute("CREATE TABLE IF NOT EXISTS \"track_event_data_" + suffix + "\" PARTITION OF track_event_data"
			+ " FOR VALUES FROM ('" + monthStart + "') TO ('" + monthEnd + "')");
	}

	/** 清理到期分区：月分区上界整体早于到期线（max retention_days）→ DETACH → DROP；返回清理个数 */
	private int dropExpiredPartitions() {
		Integer maxRetention = jdbc.queryForObject(
			"SELECT max(retention_days) FROM track_app WHERE is_deleted = 0", Integer.class);
		if (maxRetention == null || maxRetention < 1) {
			// 无应用：以系统默认保留期为到期线（防新库误清）
			maxRetention = TrackConstants.DEFAULT_RETENTION_DAYS;
		}
		LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(maxRetention);
		int dropped = 0;
		dropped += dropExpired("track_event", EVENT_PART_NAME, cutoff);
		dropped += dropExpired("track_event_data", EVENT_DATA_PART_NAME, cutoff);
		return dropped;
	}

	/** 单表到期分区清理：DETACH（摘出分区约束）后 DROP 为普通表（默认不归档） */
	private int dropExpired(String parentTable, Pattern namePattern, LocalDate cutoff) {
		List<String> partitions = jdbc.queryForList(
			"SELECT inhrelid::regclass::text FROM pg_inherits WHERE inhparent = ?::regclass", String.class, parentTable);
		int dropped = 0;
		for (String fullName : partitions) {
			String name = fullName.contains(".") ? fullName.substring(fullName.lastIndexOf('.') + 1) : fullName;
			Matcher matcher = namePattern.matcher(name);
			if (!matcher.matches()) {
				continue;
			}
			YearMonth month = YearMonth.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
			// 月分区上界 = 次月 1 日；整体早于到期线才可删（含边界日不删：上界=到期线当日内仍可能有有效数据）
			LocalDate monthEnd = month.plusMonths(1).atDay(1);
			if (monthEnd.isAfter(cutoff)) {
				continue;
			}
			jdbc.execute("ALTER TABLE " + parentTable + " DETACH PARTITION \"" + name + "\"");
			jdbc.execute("DROP TABLE IF EXISTS \"" + name + "\"");
			log.info("到期分区已清理：{}（分区上界 {}，到期线 {}）", name, monthEnd, cutoff);
			dropped++;
		}
		return dropped;
	}

	/** 默认分区巡检：非空 = 预建失败或数据越界，告警 + 计数（不自动搬移，人工介入） */
	private void checkDefaultPartitions() {
		long eventRows = countRows("track_event_default");
		long dataRows = countRows("track_event_data_default");
		if (eventRows + dataRows > 0) {
			log.error("兜底默认分区存在残留数据：track_event_default {} 行，track_event_data_default {} 行"
				+ "（分区预建失败或 received_at 越界，请人工核查）", eventRows, dataRows);
			metrics.counter(TrackConstants.METRIC_DEFAULT_PARTITION_ROWS).increment(eventRows + dataRows);
		}
	}

	private long countRows(String table) {
		Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
		return count == null ? 0L : count;
	}
}
