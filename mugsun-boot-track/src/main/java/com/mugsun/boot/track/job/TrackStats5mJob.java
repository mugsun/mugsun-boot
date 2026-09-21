package com.mugsun.boot.track.job;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.TrackDS;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 5 分钟窗口 rollup（track_stats_5m）：每分钟 tick、内存节流 5 分钟一轮、Redis 分布式锁防多副本并发。
 * <p>维度：event（事件名）/ page（路由模板，空回退 url_path）/ referrer（来源域名）/ device（设备类型）。
 * 只放可加和指标；去重类（UV/跳出）只进 day 表（§4.4）。
 * <p><b>幂等钉死</b>：每窗口全量重算 + {@code ON CONFLICT … DO UPDATE SET 值=EXCLUDED.值} 覆盖，严禁累加——
 * 任务重跑/游标回拨补扫同窗口不会双倍计数。
 * <p><b>采样口径</b>：sample_rate&lt;100 时 pv/event_count 除以采样率还原（采样估计）；
 * session_count/duration_sum 不外推（session_count 仅标注口径）。
 */
@Component
@TrackDS
public class TrackStats5mJob extends AbstractTrackRollupJob {

	/** 窗口粒度（分钟）：5 分钟 */
	private static final long WINDOW_MINUTES = 5L;

	/** 幂等覆盖 upsert：唯一键 (app_key, dim_type, dim_key, bucket_time)，值一律 EXCLUDED 覆盖（非累加） */
	private static final String UPSERT_SQL = "INSERT INTO track_stats_5m (id, app_key, bucket_time, dim_type, dim_key,"
		+ " tenant_id, pv, event_count, session_count, duration_sum, create_time, is_deleted)"
		+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), 0)"
		+ " ON CONFLICT (app_key, dim_type, dim_key, bucket_time) DO UPDATE SET"
		+ " tenant_id = EXCLUDED.tenant_id, pv = EXCLUDED.pv, event_count = EXCLUDED.event_count,"
		+ " session_count = EXCLUDED.session_count, duration_sum = EXCLUDED.duration_sum";

	/** 窗口维度聚合（event）：$pageview 计 pv；session_count 为窗口活跃会话去重（不外推） */
	private static final String AGG_EVENT = "SELECT event_name AS dim,"
		+ " count(*) FILTER (WHERE event_name = '" + TrackConstants.EVENT_PAGEVIEW + "') AS pv,"
		+ " count(*) AS cnt, count(DISTINCT session_id) AS sessions, coalesce(sum(duration_ms), 0) AS dur"
		+ " FROM track_event WHERE app_key = ? AND received_at >= ? AND received_at < ?"
		+ " GROUP BY event_name";
	/** 窗口维度聚合（page）：路由模板防高基数，空回退 url_path；截断至维度列长 */
	private static final String AGG_PAGE = "SELECT left(coalesce(nullif(route_path, ''), url_path), " + TrackConstants.DIM_MAX_LEN + ") AS dim,"
		+ " count(*) FILTER (WHERE event_name = '" + TrackConstants.EVENT_PAGEVIEW + "') AS pv,"
		+ " count(*) AS cnt, count(DISTINCT session_id) AS sessions, coalesce(sum(duration_ms), 0) AS dur"
		+ " FROM track_event WHERE app_key = ? AND received_at >= ? AND received_at < ?"
		+ " AND coalesce(nullif(route_path, ''), url_path) IS NOT NULL"
		+ " GROUP BY 1";
	/** 窗口维度聚合（referrer）：无来源域名（直访）的行不进维度（防 NULL 撑唯一索引） */
	private static final String AGG_REFERRER = "SELECT referrer_domain AS dim,"
		+ " count(*) FILTER (WHERE event_name = '" + TrackConstants.EVENT_PAGEVIEW + "') AS pv,"
		+ " count(*) AS cnt, count(DISTINCT session_id) AS sessions, coalesce(sum(duration_ms), 0) AS dur"
		+ " FROM track_event WHERE app_key = ? AND received_at >= ? AND received_at < ?"
		+ " AND referrer_domain IS NOT NULL AND referrer_domain <> ''"
		+ " GROUP BY referrer_domain";
	/** 窗口维度聚合（device）：消费侧 UA 解析/props 自报，仍可能为空则跳过 */
	private static final String AGG_DEVICE = "SELECT device AS dim,"
		+ " count(*) FILTER (WHERE event_name = '" + TrackConstants.EVENT_PAGEVIEW + "') AS pv,"
		+ " count(*) AS cnt, count(DISTINCT session_id) AS sessions, coalesce(sum(duration_ms), 0) AS dur"
		+ " FROM track_event WHERE app_key = ? AND received_at >= ? AND received_at < ?"
		+ " AND device IS NOT NULL AND device <> ''"
		+ " GROUP BY device";

	private final TrackJobGuard guard;
	/** 上次执行时间戳（本节点内存节流；多节点各自节流 + 分布式锁兜底） */
	private volatile long lastRunAt;

	public TrackStats5mJob(DataSource dataSource, TrackJobGuard guard) {
		super(dataSource);
		this.guard = guard;
	}

	/** 每分钟 tick：到 5 分钟节流间隔才执行（游标补扫 + 开放窗口重算） */
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
		return guard.withLock(TrackConstants.LOCK_STATS_5M, this::doRollup, "未获调度锁（他节点执行中），跳过本轮");
	}

	/** 一轮全应用补扫（package-private 供测试直调；逐应用隔离异常，单应用失败不拖垮整轮） */
	String doRollup() {
		List<AppScope> apps = listApps();
		int windows = 0;
		for (AppScope app : apps) {
			try {
				windows += rollupApp(app, WINDOW_MINUTES, TrackConstants.ROLLUP_5M_MAX_WINDOWS, true);
			} catch (Exception e) {
				log.warn("5m rollup 单应用失败（下轮游标补扫自愈）：appKey={}，{}", app.appKey(), e.getMessage());
			}
		}
		String summary = "5m rollup 完成：应用 " + apps.size() + " 个，补扫闭合窗口 " + windows + " 个";
		log.info(summary);
		return summary;
	}

	@Override
	protected String jobKey() {
		return TrackConstants.CURSOR_JOB_STATS_5M;
	}

	/** 窗口全量重算 + 覆盖写入：四维各一次明细扫描（BRIN 裁剪，§9.1 实测 37ms 级），空窗口零写入 */
	@Override
	protected void aggregateWindow(AppScope app, LocalDateTime bucketStart) {
		OffsetDateTime from = bucketStart.atOffset(ZoneOffset.UTC);
		OffsetDateTime to = from.plusMinutes(WINDOW_MINUTES);
		List<StatRow> rows = new ArrayList<>();
		rows.addAll(queryDim(AGG_EVENT, TrackConstants.DIM_EVENT, app.appKey(), from, to));
		rows.addAll(queryDim(AGG_PAGE, TrackConstants.DIM_PAGE, app.appKey(), from, to));
		rows.addAll(queryDim(AGG_REFERRER, TrackConstants.DIM_REFERRER, app.appKey(), from, to));
		rows.addAll(queryDim(AGG_DEVICE, TrackConstants.DIM_DEVICE, app.appKey(), from, to));
		if (rows.isEmpty()) {
			return;
		}
		jdbc.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				StatRow row = rows.get(i);
				ps.setLong(1, IdUtil.getSnowflakeNextId());
				ps.setString(2, app.appKey());
				ps.setObject(3, bucketStart);
				ps.setString(4, row.dimType());
				ps.setString(5, row.dimKey());
				ps.setString(6, rowTenant(app.tenantId()));
				ps.setLong(7, extrapolate(row.pv(), app.sampleRate()));
				ps.setLong(8, extrapolate(row.eventCount(), app.sampleRate()));
				ps.setLong(9, row.sessionCount());
				ps.setLong(10, row.durationSum());
			}

			@Override
			public int getBatchSize() {
				return rows.size();
			}
		});
	}

	/** 执行一个维度聚合（app_key + 接收时间窗绑定） */
	private List<StatRow> queryDim(String sql, String dimType, String appKey, OffsetDateTime from, OffsetDateTime to) {
		return jdbc.query(sql, (rs, i) -> new StatRow(dimType, rs.getString("dim"),
				rs.getLong("pv"), rs.getLong("cnt"), rs.getLong("sessions"), rs.getLong("dur")),
			appKey, from, to);
	}

	/** 一行维度聚合结果 */
	private record StatRow(String dimType, String dimKey, long pv, long eventCount, long sessionCount, long durationSum) {
	}
}
