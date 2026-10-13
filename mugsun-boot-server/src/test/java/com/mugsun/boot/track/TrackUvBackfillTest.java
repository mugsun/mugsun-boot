package com.mugsun.boot.track;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.job.TrackStatsDayJob;
import com.mugsun.boot.track.job.TrackUvBackfillJob;
import com.mybatisflex.core.datasource.DataSourceKey;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 历史日表 UV 回填：旧口径行被改成 distinct_id 口径、旧值先进备份表、其它列不动、
 * 无明细的日子不被清零、超出天数范围的日子不动、重复执行零改动（幂等）。
 */
class TrackUvBackfillTest extends AbstractTrackIntegrationTest {

	private static final String APP = "it-uv-bf";

	@Autowired
	private TrackEventStore store;
	@Autowired
	private TrackStatsDayJob statsDayJob;
	@Autowired
	private TrackUvBackfillJob backfillJob;

	private void seedApp() {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(
			"INSERT INTO track_app (id, app_key, app_name, platform, tenant_id, sample_rate, enabled,"
				+ " create_time, update_time, is_deleted) VALUES (?, ?, ?, 'web', ?, 100, 1, now(), now(), 0)"
				+ " ON CONFLICT (app_key) WHERE is_deleted = 0 DO NOTHING",
			IdUtil.getSnowflakeNextId(), APP, "UV 回填测试", PLATFORM_TENANT));
	}

	private void trackUpdate(String sql, Object... args) {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(sql, args));
	}

	private TrackIngestEvent ev(String name, long ms, String distinctId, String sessionId) {
		TrackIngestEvent e = new TrackIngestEvent();
		e.setEventId(UUID.randomUUID().toString());
		e.setAppKey(APP);
		e.setEventName(name);
		e.setClientTsMs(ms);
		e.setTsMs(ms);
		e.setReceivedAtMs(ms);
		e.setClockSkewed(0);
		e.setDistinctId(distinctId);
		e.setSessionId(sessionId);
		e.setTenantId(PLATFORM_TENANT);
		e.setUrlPath("/home");
		e.setRoutePath("/home");
		e.setDevice("desktop");
		e.setPropsJson("{}");
		return e;
	}

	private void insertDayRow(LocalDate day, String dimType, String dimKey, long pv, long uv) {
		trackUpdate("INSERT INTO track_stats_day (id, app_key, stat_date, dim_type, dim_key, tenant_id, pv, uv,"
				+ " session_count, bounce_count, duration_sum, event_count, create_time, update_time, is_deleted)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, now(), now(), 0)"
				+ " ON CONFLICT (app_key, dim_type, dim_key, stat_date) DO UPDATE SET pv = EXCLUDED.pv, uv = EXCLUDED.uv",
			IdUtil.getSnowflakeNextId(), APP, day, dimType, dimKey, PLATFORM_TENANT, pv, uv);
	}

	private Row dayRow(LocalDate day, String dimType, String dimKey) {
		return trackRow("SELECT pv, uv, event_count FROM track_stats_day WHERE app_key = ? AND stat_date = ?"
			+ " AND dim_type = ? AND dim_key = ?", APP, day, dimType, dimKey);
	}

	@Test
	void backfillRewritesOldUvWithBackupAndIsIdempotent() {
		seedApp();
		LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(3);
		LocalDate noDetail = LocalDate.now(ZoneOffset.UTC).minusDays(5);
		LocalDate outOfRange = LocalDate.now(ZoneOffset.UTC).minusDays(40);
		long base = day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
		String d1 = UUID.randomUUID().toString();
		String d2 = UUID.randomUUID().toString();
		// 两个设备绑同一登录用户：旧口径 UV=1，新口径（distinct_id）UV=2
		store.upsertIdentities(List.of(
			new TrackEventStore.IdentityBinding(APP, d1, 2001L, PLATFORM_TENANT),
			new TrackEventStore.IdentityBinding(APP, d2, 2001L, PLATFORM_TENANT)));
		store.insertEvents(List.of(
			ev("$pageview", base, d1, UUID.randomUUID().toString()),
			ev("$pageview", base + 1000, d2, UUID.randomUUID().toString())));
		// 先让日表任务追平（之后本日不会再被定时任务改写），再把这天改回旧口径的值
		statsDayJob.rollupNow();
		insertDayRow(day, "overview", "ALL", 2, 1);
		insertDayRow(day, "event", "$pageview", 2, 1);
		insertDayRow(day, "page", "/home", 2, 2); // 已经是新口径，不应改动、不应备份
		insertDayRow(noDetail, "overview", "ALL", 9, 7); // 明细已清理：不能被改成 0
		insertDayRow(outOfRange, "overview", "ALL", 9, 7); // 超出 days 范围

		TrackUvBackfillJob.Result r1 = backfillJob.backfill(30, APP);
		assertThat(r1.locked()).isTrue();
		assertThat(r1.updatedRows()).isEqualTo(2L);

		assertThat(dayRow(day, "overview", "ALL").getLong("uv")).isEqualTo(2L);
		assertThat(dayRow(day, "overview", "ALL").getLong("pv")).as("只改 uv").isEqualTo(2L);
		assertThat(dayRow(day, "event", "$pageview").getLong("uv")).isEqualTo(2L);
		assertThat(dayRow(day, "page", "/home").getLong("uv")).isEqualTo(2L);
		assertThat(dayRow(noDetail, "overview", "ALL").getLong("uv")).as("无明细不清零").isEqualTo(7L);
		assertThat(dayRow(outOfRange, "overview", "ALL").getLong("uv")).as("超出范围不动").isEqualTo(7L);

		long backups = trackLong("SELECT count(*) AS c FROM track_stats_day_uv_backup WHERE run_id = ?", r1.runId());
		assertThat(backups).isEqualTo(2L);
		Row bak = trackRow("SELECT old_uv, new_uv FROM track_stats_day_uv_backup WHERE run_id = ?"
			+ " AND dim_type = 'overview'", r1.runId());
		assertThat(bak.getLong("old_uv")).isEqualTo(1L);
		assertThat(bak.getLong("new_uv")).isEqualTo(2L);

		// 幂等：再跑一遍零改动、零备份
		TrackUvBackfillJob.Result r2 = backfillJob.backfill(30, APP);
		assertThat(r2.updatedRows()).isZero();
		assertThat(trackLong("SELECT count(*) AS c FROM track_stats_day_uv_backup WHERE run_id = ?", r2.runId())).isZero();

		// 按 run_id 还原（升级说明里的 SQL）
		trackUpdate("UPDATE track_stats_day d SET uv = b.old_uv FROM track_stats_day_uv_backup b"
			+ " WHERE b.run_id = ? AND d.id = b.stats_day_id", r1.runId());
		assertThat(dayRow(day, "overview", "ALL").getLong("uv")).isEqualTo(1L);
	}

	@Test
	void rejectsOutOfRangeDays() {
		assertThatThrownBy(() -> backfillJob.backfill(0, APP)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> backfillJob.backfill(5000, APP)).isInstanceOf(IllegalArgumentException.class);
	}
}
