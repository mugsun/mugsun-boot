package com.mugsun.boot.track.job;

import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.TrackDS;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历史 track_stats_day UV 回填：按 {@link TrackConstants#UV_EXPR}（distinct_id 口径）从明细重算已有日表行的 uv。
 * <ul>
 *   <li><b>幂等</b>：只改 uv 与重算值不同的行；重复执行第二遍改动 0 行、也不再写备份。</li>
 *   <li><b>先备份</b>：每行改动前把旧值写入 {@code track_stats_day_uv_backup}（run_id 可整批还原），
 *       备份与更新在同一条 SQL 内完成（原子）。</li>
 *   <li><b>分批</b>：一条语句只处理一个应用的一天，单语句短事务，只锁该应用该日的日表行，不锁表。</li>
 *   <li><b>只改 uv</b>：pv/会话/时长等其它列不动；不新增行；明细已被保留期清理（当日无事件）的日子跳过，
 *       不会把历史 UV 改成 0；当日明细里已不存在的维度行也跳过。</li>
 *   <li>范围：UTC 闭合日 [今天-days, 昨天]，days 默认 90（{@code mugsun.track.uv-backfill.days}）。</li>
 * </ul>
 */
@Component
@TrackDS
public class TrackUvBackfillJob extends AbstractTrackRollupJob {

	/** 分布式锁：同一时刻只跑一份回填 */
	static final String LOCK_UV_BACKFILL = TrackConstants.REDIS_PREFIX + "lock:uv-backfill";
	/** 回填天数上限（防误配一次扫全表） */
	static final int MAX_DAYS = 3660;

	/**
	 * 单日单应用：备份变化行旧值 + 覆盖 uv，一条语句原子完成。
	 * 参数：dim_type[], dim_key[], uv[], app_key, stat_date, run_id
	 */
	private static final String BACKFILL_SQL = "WITH fresh AS ("
		+ " SELECT * FROM unnest(?::varchar[], ?::varchar[], ?::bigint[]) AS f(dim_type, dim_key, uv)),"
		+ " changed AS ("
		+ " SELECT d.id, d.app_key, d.stat_date, d.dim_type, d.dim_key, d.uv AS old_uv, f.uv AS new_uv"
		+ " FROM track_stats_day d JOIN fresh f ON d.dim_type = f.dim_type AND d.dim_key = f.dim_key"
		+ " WHERE d.app_key = ? AND d.stat_date = ? AND d.is_deleted = 0 AND d.uv <> f.uv"
		+ " FOR UPDATE OF d),"
		+ " bak AS (INSERT INTO track_stats_day_uv_backup"
		+ " (run_id, stats_day_id, app_key, stat_date, dim_type, dim_key, old_uv, new_uv, backup_time)"
		+ " SELECT ?, id, app_key, stat_date, dim_type, dim_key, old_uv, new_uv, now() FROM changed RETURNING 1),"
		+ " upd AS (UPDATE track_stats_day d SET uv = c.new_uv, update_time = now()"
		+ " FROM changed c WHERE d.id = c.id RETURNING 1)"
		+ " SELECT (SELECT count(*) FROM upd) AS updated, (SELECT count(*) FROM bak) AS backed";

	private final TrackJobGuard guard;
	private final int defaultDays;

	public TrackUvBackfillJob(DataSource dataSource, TrackJobGuard guard,
							  @Value("${mugsun.track.uv-backfill.days:90}") int defaultDays) {
		super(dataSource);
		this.guard = guard;
		this.defaultDays = defaultDays;
	}

	/** 回填结果 */
	public record Result(String runId, int apps, int days, int skippedDays, long updatedRows, boolean locked) {
		public String summary() {
			if (!locked) {
				return "UV 回填未获锁（其它节点执行中），跳过";
			}
			return "UV 回填完成：run_id=" + runId + "，应用 " + apps + " 个，处理 " + days + " 天（无明细跳过 "
				+ skippedDays + " 天），改动 " + updatedRows + " 行（旧值已备份到 track_stats_day_uv_backup）";
		}
	}

	/** 默认回填天数 */
	public int defaultDays() {
		return defaultDays;
	}

	/** 按默认天数回填全部应用 */
	public Result backfill() {
		return backfill(defaultDays, null);
	}

	/**
	 * @param days   回填最近多少个闭合日（1..3660）
	 * @param appKey 只回填该应用；null/空表示全部应用
	 */
	public Result backfill(int days, String appKey) {
		if (days < 1 || days > MAX_DAYS) {
			throw new IllegalArgumentException("回填天数需在 1 到 " + MAX_DAYS + " 之间");
		}
		Result miss = new Result(null, 0, 0, 0, 0, false);
		return guard.withLock(LOCK_UV_BACKFILL, () -> doBackfill(days, appKey), miss);
	}

	Result doBackfill(int days, String appKey) {
		String runId = "uvbf-" + OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
			+ "-" + Long.toHexString(System.nanoTime() & 0xffffL);
		LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
		LocalDate first = yesterday.minusDays(days - 1L);
		List<AppScope> apps = listApps().stream()
			.filter(a -> appKey == null || appKey.isBlank() || appKey.equals(a.appKey()))
			.toList();
		int processedDays = 0;
		int skipped = 0;
		long updated = 0;
		for (AppScope app : apps) {
			for (LocalDate day = first; !day.isAfter(yesterday); day = day.plusDays(1)) {
				Long n = backfillDay(app.appKey(), day, runId);
				if (n == null) {
					skipped++;
				} else {
					processedDays++;
					updated += n;
				}
			}
		}
		Result result = new Result(runId, apps.size(), processedDays, skipped, updated, true);
		log.info(result.summary());
		return result;
	}

	/** 单应用单日：返回改动行数；该日无日表行或无明细返回 null（跳过） */
	Long backfillDay(String appKey, LocalDate day, String runId) {
		Long existing = jdbc.queryForObject("SELECT count(*) FROM track_stats_day"
			+ " WHERE app_key = ? AND stat_date = ? AND is_deleted = 0", Long.class, appKey, day);
		if (existing == null || existing == 0) {
			return null;
		}
		LocalDateTime dayStart = day.atStartOfDay();
		OffsetDateTime from = dayStart.atOffset(ZoneOffset.UTC);
		OffsetDateTime to = from.plusDays(1);
		Map<String, Long> fresh = new LinkedHashMap<>();
		long[] eventCount = {0};
		jdbc.query(TrackStatsDayJob.AGG_OVERVIEW, (ResultSet rs) -> {
			eventCount[0] = rs.getLong("cnt");
			fresh.put(TrackConstants.DIM_OVERVIEW + "\u0000" + TrackConstants.DIM_KEY_ALL, rs.getLong("uv"));
		}, appKey, from, to);
		if (eventCount[0] == 0) {
			// 明细已被保留期清理（或本来就没有事件）：无从重算，保持原值
			return null;
		}
		for (String sql : List.of(TrackStatsDayJob.AGG_EVENT, TrackStatsDayJob.AGG_PAGE,
			TrackStatsDayJob.AGG_REFERRER, TrackStatsDayJob.AGG_DEVICE)) {
			jdbc.query(sql, (ResultSet rs) -> {
				fresh.put(rs.getString("dimType") + "\u0000" + rs.getString("dim"), rs.getLong("uv"));
			}, appKey, from, to);
		}
		List<String> types = new ArrayList<>(fresh.size());
		List<String> keys = new ArrayList<>(fresh.size());
		List<Long> uvs = new ArrayList<>(fresh.size());
		fresh.forEach((k, v) -> {
			int i = k.indexOf('\u0000');
			types.add(k.substring(0, i));
			keys.add(k.substring(i + 1));
			uvs.add(v);
		});
		return jdbc.execute((Connection c) -> {
			try (PreparedStatement ps = c.prepareStatement(BACKFILL_SQL)) {
				Array t = c.createArrayOf("varchar", types.toArray());
				Array k = c.createArrayOf("varchar", keys.toArray());
				Array u = c.createArrayOf("bigint", uvs.toArray());
				ps.setArray(1, t);
				ps.setArray(2, k);
				ps.setArray(3, u);
				ps.setString(4, appKey);
				ps.setObject(5, day);
				ps.setString(6, runId);
				try (ResultSet rs = ps.executeQuery()) {
					return rs.next() ? rs.getLong("updated") : 0L;
				}
			}
		});
	}

	@Override
	protected String jobKey() {
		return "uv_backfill";
	}

	@Override
	protected void aggregateWindow(AppScope app, LocalDateTime bucketStart) {
		throw new UnsupportedOperationException("UV 回填不走游标补扫");
	}
}
