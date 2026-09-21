package com.mugsun.boot.track.job;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.TrackDS;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Web Vitals 直方图 rollup（track_stats_vitals，日粒度窗口）：每分钟 tick、5 分钟节流、Redis 锁。
 * <p>$web_vitals 明细按 metric（lcp/inp/cls/fcp/ttfb）落对数直方图桶——桶界写死 {@link TrackConstants}
 * （LCP/INP/FCP/TTFB 毫秒对数桶 100/250/500/1000/2500/5000/10000/30000；CLS 千分制 10/25/50/100/250/500/1000），
 * 看板插值 p50/p75/p95 替实时 percentile_cont（§9.1 实测 764ms→&lt;10ms）。
 * <p><b>幂等钉死（§4.4「先清后写」）</b>：同一 stat_date 重算 = 事务内 DELETE 当日 + 重算 INSERT
 * （游标仅在整窗成功后推进，失败下轮自愈）；url_path 空值写 {@link TrackConstants#VITALS_DIM_UNKNOWN} 空串
 * （唯一索引含 url_path，NULL 会使 ON CONFLICT 失配产生重复行）。
 * <p>游标补扫闭合日之外，每轮重算当日（开放日）——vitals 看板当日可见。
 */
@Component
@TrackDS
public class TrackVitalsJob extends AbstractTrackRollupJob {

	/** 窗口粒度（分钟）：1440 = UTC 日界（track_stats_vitals.stat_date 为 DATE） */
	private static final long WINDOW_MINUTES = 1440L;

	/** 直方图分桶聚合：值经数值正则护栏后转 numeric（脏值不入桶）；无路径信息归空串占位 */
	private static final String HISTOGRAM_SQL = "SELECT props->>'" + TrackConstants.PROP_VITALS_METRIC + "' AS metric,"
		+ " coalesce(nullif(route_path, ''), url_path, '" + TrackConstants.VITALS_DIM_UNKNOWN + "') AS dim,"
		+ " " + bucketCaseSql() + " AS bucket, count(*) AS cnt"
		+ " FROM track_event"
		+ " WHERE app_key = ? AND event_name = '$web_vitals' AND received_at >= ? AND received_at < ?"
		+ " AND props->>'" + TrackConstants.PROP_VITALS_METRIC + "' IN ('lcp', 'inp', 'cls', 'fcp', 'ttfb')"
		+ " AND props->>'" + TrackConstants.PROP_VITALS_VALUE + "' ~ '^[0-9]+(\\.[0-9]+)?$'"
		+ " GROUP BY 1, 2, 3";

	/** 同窗口重写后的覆盖 upsert（事务内 delete 已清空，此处为双保险） */
	private static final String INSERT_SQL = "INSERT INTO track_stats_vitals"
		+ " (id, app_key, stat_date, metric, url_path, bucket, cnt, tenant_id, create_time, update_time)"
		+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())"
		+ " ON CONFLICT (app_key, stat_date, metric, url_path, bucket)"
		+ " DO UPDATE SET cnt = EXCLUDED.cnt, update_time = now()";

	private static final String DELETE_DAY_SQL = "DELETE FROM track_stats_vitals WHERE app_key = ? AND stat_date = ?";

	private final TrackJobGuard guard;
	/** 当日重写（delete+insert）须原子：事务模板（路由数据源在 @TrackDS 切面内取 track 连接） */
	private final TransactionTemplate tx;
	/** 上次执行时间戳（内存节流；多节点各自节流 + 分布式锁兜底） */
	private volatile long lastRunAt;

	public TrackVitalsJob(DataSource dataSource, TrackJobGuard guard) {
		super(dataSource);
		this.guard = guard;
		this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
	}

	/** 每分钟 tick：到 5 分钟节流间隔才执行 */
	@Scheduled(fixedDelay = TrackConstants.ROLLUP_SCHED_TICK_MS)
	public void tick() {
		long now = System.currentTimeMillis();
		if (now - lastRunAt < TrackConstants.ROLLUP_TICK_MS) {
			return;
		}
		lastRunAt = now;
		rollupNow();
	}

	/** 供 PowerJob 处理器/集成测试手动触发：立即执行一轮（不经节流；带分布式锁），返回执行摘要 */
	public String rollupNow() {
		return guard.withLock(TrackConstants.LOCK_STATS_VITALS, this::doRollup, "未获调度锁（他节点执行中），跳过本轮");
	}

	/** 一轮全应用补扫（package-private 供测试直调；逐应用隔离异常） */
	String doRollup() {
		List<AppScope> apps = listApps();
		int windows = 0;
		for (AppScope app : apps) {
			try {
				windows += rollupApp(app, WINDOW_MINUTES, TrackConstants.ROLLUP_DAY_MAX_WINDOWS, true);
			} catch (Exception e) {
				log.warn("vitals rollup 单应用失败（下轮游标补扫自愈）：appKey={}，{}", app.appKey(), e.getMessage());
			}
		}
		String summary = "vitals rollup 完成：应用 " + apps.size() + " 个，补扫闭合日 " + windows + " 天";
		log.info(summary);
		return summary;
	}

	@Override
	protected String jobKey() {
		return TrackConstants.CURSOR_JOB_STATS_VITALS;
	}

	/** 单日直方图重算：事务内先清当日再写（幂等可重入；游标整窗成功后才推进） */
	@Override
	protected void aggregateWindow(AppScope app, LocalDateTime dayStart) {
		LocalDate statDate = dayStart.toLocalDate();
		OffsetDateTime from = dayStart.atOffset(ZoneOffset.UTC);
		OffsetDateTime to = from.plusDays(1);
		tx.executeWithoutResult(status -> {
			jdbc.update(DELETE_DAY_SQL, app.appKey(), statDate);
			List<BucketRow> rows = jdbc.query(HISTOGRAM_SQL,
				(rs, i) -> new BucketRow(rs.getString("metric"), rs.getString("dim"),
					rs.getInt("bucket"), rs.getLong("cnt")),
				app.appKey(), from, to);
			if (rows.isEmpty()) {
				return;
			}
			jdbc.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
				@Override
				public void setValues(PreparedStatement ps, int i) throws SQLException {
					BucketRow row = rows.get(i);
					ps.setLong(1, IdUtil.getSnowflakeNextId());
					ps.setString(2, app.appKey());
					ps.setObject(3, statDate);
					ps.setString(4, row.metric());
					ps.setString(5, row.dim());
					ps.setInt(6, row.bucket());
					ps.setLong(7, row.cnt());
					ps.setString(8, rowTenant(app.tenantId()));
				}

				@Override
				public int getBatchSize() {
					return rows.size();
				}
			});
		});
	}

	/** 分桶 CASE 表达式（桶界定自 TrackConstants；CLS 千分制桶，其余毫秒对数桶） */
	private static String bucketCaseSql() {
		String value = "((props->>'" + TrackConstants.PROP_VITALS_VALUE + "')::numeric)";
		return "CASE WHEN props->>'" + TrackConstants.PROP_VITALS_METRIC + "' = '" + TrackConstants.VITALS_METRIC_CLS + "'"
			+ " THEN " + buildCase(value, TrackConstants.VITALS_CLS_BUCKET_BOUNDS)
			+ " ELSE " + buildCase(value, TrackConstants.VITALS_MS_BUCKET_BOUNDS) + " END";
	}

	/** 由桶界数组生成 CASE：桶序号 = 小于边界的个数（0..bounds.length，末号桶为溢出桶） */
	private static String buildCase(String valueExpr, long[] bounds) {
		StringBuilder sb = new StringBuilder("CASE");
		for (int i = 0; i < bounds.length; i++) {
			sb.append(" WHEN ").append(valueExpr).append(" < ").append(bounds[i]).append(" THEN ").append(i);
		}
		sb.append(" ELSE ").append(bounds.length).append(" END");
		return sb.toString();
	}

	/** 一行桶计数（dim = 路由模板/原始路径/空串占位） */
	private record BucketRow(String metric, String dim, int bucket, long cnt) {
	}
}
