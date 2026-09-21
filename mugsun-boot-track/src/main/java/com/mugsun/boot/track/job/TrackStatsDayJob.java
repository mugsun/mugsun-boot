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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 天级 rollup（track_stats_day）：每小时 tick，当日已追平则跳过（凌晨新一轮生效）；游标补扫闭合日。
 * <p>分日基准 = received_at（UTC 日界；迟到数据归入接收当日，历史不被追改）。
 * 聚合昨日（含）以前的闭合日；今日由看板「当日分区直算」覆盖，本任务不写开放日。
 * <p><b>口径钉死（§4.4/§6）</b>：
 * <br>① UV 精确去重：{@code count(distinct coalesce(m.user_id::text, e.distinct_id))}
 * LEFT JOIN track_identity 归并（跨设备同 user 合一；UV 不可从子窗口相加，必须从明细算）；
 * <br>② overview 行（dim_key=ALL）的 session_count/bounce_count 来自 track_session
 * （按 start_time 归日；跳出 = pageviews&lt;=1，与结算任务 is_bounce 定义一致，不依赖 settled 时序）；
 * <br>③ 维度行（event/page/referrer/device）的 session_count 为当日明细 count(distinct session_id)，bounce_count 恒 0；
 * <br>④ 采样外推仅 pv/event_count（量级类）；uv/session_count/bounce_count/duration_sum 不外推。
 * <p><b>幂等钉死</b>：每日全量重算 + SET 覆盖 upsert，严禁累加。
 */
@Component
@TrackDS
public class TrackStatsDayJob extends AbstractTrackRollupJob {

	/** 窗口粒度（分钟）：1440 = UTC 日界 */
	private static final long WINDOW_MINUTES = 1440L;

	/** 幂等覆盖 upsert：唯一键 (app_key, dim_type, dim_key, stat_date) */
	private static final String UPSERT_SQL = "INSERT INTO track_stats_day (id, app_key, stat_date, dim_type, dim_key,"
		+ " tenant_id, pv, uv, session_count, bounce_count, duration_sum, event_count,"
		+ " create_time, update_time, is_deleted)"
		+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now(), 0)"
		+ " ON CONFLICT (app_key, dim_type, dim_key, stat_date) DO UPDATE SET"
		+ " tenant_id = EXCLUDED.tenant_id, pv = EXCLUDED.pv, uv = EXCLUDED.uv,"
		+ " session_count = EXCLUDED.session_count, bounce_count = EXCLUDED.bounce_count,"
		+ " duration_sum = EXCLUDED.duration_sum, event_count = EXCLUDED.event_count, update_time = now()";

	/** 身份归并 JOIN（UV 唯一事实源；首绑不覆盖语义见 §4.7） */
	private static final String IDENTITY_JOIN = " FROM track_event e LEFT JOIN track_identity m"
		+ " ON m.app_key = e.app_key AND m.distinct_id = e.distinct_id AND m.is_deleted = 0"
		+ " WHERE e.app_key = ? AND e.received_at >= ? AND e.received_at < ?";

	/** overview 行：事件侧指标（session_count/bounce_count 另查 track_session 覆盖） */
	private static final String AGG_OVERVIEW = "SELECT count(*) FILTER (WHERE e.event_name = '"
		+ TrackConstants.EVENT_PAGEVIEW + "') AS pv, count(*) AS cnt,"
		+ " count(DISTINCT coalesce(m.user_id::text, e.distinct_id)) AS uv,"
		+ " coalesce(sum(e.duration_ms), 0) AS dur" + IDENTITY_JOIN;

	/** overview 行：会话侧指标（按 start_time 归日；跳出 = pageviews<=1，与结算定稿同定义） */
	private static final String AGG_SESSION = "SELECT count(*) AS sessions,"
		+ " count(*) FILTER (WHERE pageviews <= 1) AS bounce"
		+ " FROM track_session WHERE app_key = ? AND start_time >= ? AND start_time < ? AND is_deleted = 0";

	/** 维度聚合（event/page/referrer/device）：UV 经 identity 归并；bounce 不适用于维度行 */
	private static final String AGG_EVENT = dimSql("e.event_name", TrackConstants.DIM_EVENT, null);
	private static final String AGG_PAGE = dimSql(
		"left(coalesce(nullif(e.route_path, ''), e.url_path), " + TrackConstants.DIM_MAX_LEN + ")", TrackConstants.DIM_PAGE,
		"coalesce(nullif(e.route_path, ''), e.url_path) IS NOT NULL");
	private static final String AGG_REFERRER = dimSql("e.referrer_domain", TrackConstants.DIM_REFERRER,
		"e.referrer_domain IS NOT NULL AND e.referrer_domain <> ''");
	private static final String AGG_DEVICE = dimSql("e.device", TrackConstants.DIM_DEVICE,
		"e.device IS NOT NULL AND e.device <> ''");

	private final TrackJobGuard guard;
	/** 当日已追平标记（UTC 日；凌晨跨日后才会触发新一轮，实现「每日一轮」节流） */
	private volatile LocalDate lastCaughtUpDay;

	public TrackStatsDayJob(DataSource dataSource, TrackJobGuard guard) {
		super(dataSource);
		this.guard = guard;
	}

	/** 每小时 tick：当日已追平则跳过（游标补扫保证正确性，频率仅是新鲜度问题） */
	@Scheduled(fixedDelay = TrackConstants.STATS_DAY_TICK_MS)
	public void tick() {
		LocalDate today = LocalDate.now(ZoneOffset.UTC);
		if (lastCaughtUpDay != null && !today.isAfter(lastCaughtUpDay)) {
			return;
		}
		rollupNow();
	}

	/** 供 PowerJob 处理器/集成测试手动触发：立即执行一轮（不经节流；带分布式锁），返回执行摘要 */
	public String rollupNow() {
		return guard.withLock(TrackConstants.LOCK_STATS_DAY, this::doRollup, "未获调度锁（他节点执行中），跳过本轮");
	}

	/** 一轮全应用补扫（package-private 供测试直调；逐应用隔离异常） */
	String doRollup() {
		List<AppScope> apps = listApps();
		int windows = 0;
		boolean allCaughtUp = true;
		for (AppScope app : apps) {
			try {
				int processed = rollupApp(app, WINDOW_MINUTES, TrackConstants.ROLLUP_DAY_MAX_WINDOWS, false);
				windows += processed;
				if (processed >= TrackConstants.ROLLUP_DAY_MAX_WINDOWS) {
					allCaughtUp = false;
				}
			} catch (Exception e) {
				allCaughtUp = false;
				log.warn("day rollup 单应用失败（下轮游标补扫自愈）：appKey={}，{}", app.appKey(), e.getMessage());
			}
		}
		if (allCaughtUp) {
			lastCaughtUpDay = LocalDate.now(ZoneOffset.UTC);
		}
		String summary = "day rollup 完成：应用 " + apps.size() + " 个，补扫闭合日 " + windows + " 天";
		log.info(summary);
		return summary;
	}

	@Override
	protected String jobKey() {
		return TrackConstants.CURSOR_JOB_STATS_DAY;
	}

	/** 闭合日全量重算：overview 行（事件侧 + 会话侧合并）+ 四维行；空日零写入 */
	@Override
	protected void aggregateWindow(AppScope app, LocalDateTime dayStart) {
		LocalDate statDate = dayStart.toLocalDate();
		OffsetDateTime from = dayStart.atOffset(ZoneOffset.UTC);
		OffsetDateTime to = from.plusDays(1);
		List<DayRow> rows = new ArrayList<>();

		DayRow overview = jdbc.queryForObject(AGG_OVERVIEW, (rs, i) -> new DayRow(TrackConstants.DIM_OVERVIEW,
			TrackConstants.DIM_KEY_ALL, rs.getLong("pv"), rs.getLong("cnt"), 0L, rs.getLong("uv"),
			rs.getLong("dur"), 0L), app.appKey(), from, to);
		long[] sessionStat = jdbc.queryForObject(AGG_SESSION,
			(rs, i) -> new long[]{rs.getLong("sessions"), rs.getLong("bounce")}, app.appKey(), dayStart, dayStart.plusDays(1));
		long sessions = sessionStat == null ? 0L : sessionStat[0];
		long bounce = sessionStat == null ? 0L : sessionStat[1];
		if (overview != null && (overview.eventCount() > 0 || sessions > 0)) {
			rows.add(new DayRow(TrackConstants.DIM_OVERVIEW, TrackConstants.DIM_KEY_ALL,
				overview.pv(), overview.eventCount(), sessions, overview.uv(), overview.durationSum(), bounce));
		}

		rows.addAll(queryDim(AGG_EVENT, app.appKey(), from, to));
		rows.addAll(queryDim(AGG_PAGE, app.appKey(), from, to));
		rows.addAll(queryDim(AGG_REFERRER, app.appKey(), from, to));
		rows.addAll(queryDim(AGG_DEVICE, app.appKey(), from, to));
		if (rows.isEmpty()) {
			return;
		}
		jdbc.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement ps, int i) throws SQLException {
				DayRow row = rows.get(i);
				ps.setLong(1, IdUtil.getSnowflakeNextId());
				ps.setString(2, app.appKey());
				ps.setObject(3, statDate);
				ps.setString(4, row.dimType());
				ps.setString(5, row.dimKey());
				ps.setString(6, rowTenant(app.tenantId()));
				ps.setLong(7, extrapolate(row.pv(), app.sampleRate()));
				ps.setLong(8, row.uv());
				ps.setLong(9, row.sessionCount());
				ps.setLong(10, row.bounceCount());
				ps.setLong(11, row.durationSum());
				ps.setLong(12, extrapolate(row.eventCount(), app.sampleRate()));
			}

			@Override
			public int getBatchSize() {
				return rows.size();
			}
		});
	}

	/** 执行一个维度聚合（UV 经 identity 归并；维度行 bounce=0） */
	private List<DayRow> queryDim(String sql, String appKey, OffsetDateTime from, OffsetDateTime to) {
		return jdbc.query(sql, (rs, i) -> new DayRow(rs.getString("dimType"), rs.getString("dim"),
				rs.getLong("pv"), rs.getLong("cnt"), rs.getLong("sessions"), rs.getLong("uv"), rs.getLong("dur"), 0L),
			appKey, from, to);
	}

	/** 维度聚合 SQL 组装（dimExpr 非空过滤条件可选；uv 经 identity 归并） */
	private static String dimSql(String dimExpr, String dimType, String dimFilter) {
		return "SELECT '" + dimType + "' AS \"dimType\", " + dimExpr + " AS dim,"
			+ " count(*) FILTER (WHERE e.event_name = '" + TrackConstants.EVENT_PAGEVIEW + "') AS pv,"
			+ " count(*) AS cnt, count(DISTINCT e.session_id) AS sessions,"
			+ " count(DISTINCT coalesce(m.user_id::text, e.distinct_id)) AS uv,"
			+ " coalesce(sum(e.duration_ms), 0) AS dur" + IDENTITY_JOIN
			+ (dimFilter == null ? "" : " AND " + dimFilter)
			+ " GROUP BY " + dimExpr;
	}

	/** 一行天级聚合结果 */
	private record DayRow(String dimType, String dimKey, long pv, long eventCount, long sessionCount,
						  long uv, long durationSum, long bounceCount) {
	}
}
