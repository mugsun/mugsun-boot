package com.mugsun.boot.track;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.job.TrackStatsDayJob;
import com.mugsun.boot.track.job.TrackUvBackfillJob;
import com.mugsun.boot.track.job.TrackUvBackfillJobTestAccess;
import com.mugsun.boot.track.job.TrackUvBackfillProperties;
import com.mybatisflex.core.datasource.DataSourceKey;
import com.mybatisflex.core.row.Db;
import com.mybatisflex.core.row.Row;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 历史日表 UV 回填：口径、备份、幂等、无明细不清零、独立锁（续期 / 并发只跑一份 / Redis 不可用不跑）、
 * 运行时段、游标续跑。
 */
class TrackUvBackfillTest extends AbstractTrackIntegrationTest {

	@Autowired
	private TrackEventStore store;
	@Autowired
	private TrackStatsDayJob statsDayJob;
	@Autowired
	private TrackUvBackfillJob springJob;
	@Autowired
	private DataSource dataSource;
	@Autowired
	private StringRedisTemplate redis;

	private TrackUvBackfillProperties props(long ttlSeconds, long sleepMillis, boolean window) {
		TrackUvBackfillProperties p = new TrackUvBackfillProperties();
		p.setLockTtlSeconds(ttlSeconds);
		p.setSleepMillis(sleepMillis);
		p.setWindowEnabled(window);
		return p;
	}

	private TrackUvBackfillJob job(TrackUvBackfillProperties p) {
		return new TrackUvBackfillJob(dataSource, redis, p);
	}

	private TrackUvBackfillJob.Result run(TrackUvBackfillJob job, int days, String app) {
		return DataSourceKey.use(TrackConstants.DS_KEY, () -> job.backfill(days, app));
	}

	private void seedApp(String app) {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(
			"INSERT INTO track_app (id, app_key, app_name, platform, tenant_id, sample_rate, enabled,"
				+ " create_time, update_time, is_deleted) VALUES (?, ?, ?, 'web', ?, 100, 1, now(), now(), 0)"
				+ " ON CONFLICT (app_key) WHERE is_deleted = 0 DO NOTHING",
			IdUtil.getSnowflakeNextId(), app, "UV 回填测试", PLATFORM_TENANT));
	}

	private void trackUpdate(String sql, Object... args) {
		DataSourceKey.use(TrackConstants.DS_KEY, () -> Db.updateBySql(sql, args));
	}

	private TrackIngestEvent ev(String app, String name, long ms, String distinctId, String routePath,
								String referrer) {
		TrackIngestEvent e = new TrackIngestEvent();
		e.setEventId(UUID.randomUUID().toString());
		e.setAppKey(app);
		e.setEventName(name);
		e.setClientTsMs(ms);
		e.setTsMs(ms);
		e.setReceivedAtMs(ms);
		e.setClockSkewed(0);
		e.setDistinctId(distinctId);
		e.setSessionId(UUID.randomUUID().toString());
		e.setTenantId(PLATFORM_TENANT);
		e.setUrlPath(routePath);
		e.setRoutePath(routePath);
		e.setReferrerDomain(referrer);
		e.setDevice("desktop");
		e.setPropsJson("{}");
		return e;
	}

	/** 某天两个设备（绑同一用户）各一条 PV：新口径 UV=2 */
	private void seedDay(String app, LocalDate day) {
		long base = day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
		String d1 = UUID.randomUUID().toString();
		String d2 = UUID.randomUUID().toString();
		store.upsertIdentities(List.of(
			new TrackEventStore.IdentityBinding(app, d1, 2001L, PLATFORM_TENANT),
			new TrackEventStore.IdentityBinding(app, d2, 2001L, PLATFORM_TENANT)));
		store.insertEvents(List.of(
			ev(app, "$pageview", base, d1, "/home", "google.com"),
			ev(app, "$pageview", base + 1000, d2, "/home", "")));
	}

	private void insertDayRow(String app, LocalDate day, String dimType, String dimKey, long pv, long uv) {
		trackUpdate("INSERT INTO track_stats_day (id, app_key, stat_date, dim_type, dim_key, tenant_id, pv, uv,"
				+ " session_count, bounce_count, duration_sum, event_count, create_time, update_time, is_deleted)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, now(), now(), 0)"
				+ " ON CONFLICT (app_key, dim_type, dim_key, stat_date) DO UPDATE SET pv = EXCLUDED.pv, uv = EXCLUDED.uv",
			IdUtil.getSnowflakeNextId(), app, day, dimType, dimKey, PLATFORM_TENANT, pv, uv);
	}

	private void setUv(String app, LocalDate day, long uv) {
		trackUpdate("UPDATE track_stats_day SET uv = ? WHERE app_key = ? AND stat_date = ?", uv, app, day);
	}

	private Row dayRow(String app, LocalDate day, String dimType, String dimKey) {
		return trackRow("SELECT pv, uv FROM track_stats_day WHERE app_key = ? AND stat_date = ?"
			+ " AND dim_type = ? AND dim_key = ?", app, day, dimType, dimKey);
	}

	private static LocalDate daysAgo(int n) {
		return LocalDate.now(ZoneOffset.UTC).minusDays(n);
	}

	@Test
	void backfillRewritesOldUvWithBackupAndIsIdempotent() {
		String app = "it-uv-bf";
		seedApp(app);
		LocalDate day = daysAgo(3);
		LocalDate noDetail = daysAgo(5);
		LocalDate outOfRange = daysAgo(40);
		seedDay(app, day);
		// 先让日表任务追平（之后不会再被定时任务改写），再改回旧口径的值
		statsDayJob.rollupNow();
		assertThat(dayRow(app, day, "overview", "ALL").getLong("uv")).as("日终任务按 distinct_id").isEqualTo(2L);
		assertThat(dayRow(app, day, "referrer", "google.com").getLong("uv")).isEqualTo(1L);
		insertDayRow(app, day, "overview", "ALL", 2, 1);
		insertDayRow(app, day, "event", "$pageview", 2, 1);
		insertDayRow(app, noDetail, "overview", "ALL", 9, 7);
		insertDayRow(app, outOfRange, "overview", "ALL", 9, 7);

		TrackUvBackfillJob j = job(props(600, 0, false));
		TrackUvBackfillJob.Result r1 = run(j, 30, app);
		assertThat(r1.status()).isEqualTo(TrackUvBackfillJob.Status.DONE);
		assertThat(r1.updatedRows()).isEqualTo(2L);
		assertThat(r1.days()).isEqualTo(1);
		assertThat(r1.noDetailDays()).isEqualTo(1);

		assertThat(dayRow(app, day, "overview", "ALL").getLong("uv")).isEqualTo(2L);
		assertThat(dayRow(app, day, "overview", "ALL").getLong("pv")).as("只改 uv").isEqualTo(2L);
		assertThat(dayRow(app, day, "event", "$pageview").getLong("uv")).isEqualTo(2L);
		assertThat(dayRow(app, day, "page", "/home").getLong("uv")).isEqualTo(2L);
		assertThat(dayRow(app, day, "referrer", "google.com").getLong("uv")).isEqualTo(1L);
		assertThat(dayRow(app, noDetail, "overview", "ALL").getLong("uv")).as("无明细不清零").isEqualTo(7L);
		assertThat(dayRow(app, outOfRange, "overview", "ALL").getLong("uv")).as("超出范围不动").isEqualTo(7L);

		assertThat(trackLong("SELECT count(*) AS c FROM track_stats_day_uv_backup WHERE run_id = ?", r1.runId()))
			.isEqualTo(2L);
		Row bak = trackRow("SELECT old_uv, new_uv FROM track_stats_day_uv_backup WHERE run_id = ?"
			+ " AND dim_type = 'overview'", r1.runId());
		assertThat(bak.getLong("old_uv")).isEqualTo(1L);
		assertThat(bak.getLong("new_uv")).isEqualTo(2L);
		assertThat(trackLong("SELECT count(*) AS c FROM track_rollup_cursor WHERE job_key = 'uv_backfill'"
			+ " AND app_key = ?", app)).as("完成后清游标").isZero();

		TrackUvBackfillJob.Result r2 = run(j, 30, app);
		assertThat(r2.updatedRows()).isZero();
		assertThat(trackLong("SELECT count(*) AS c FROM track_stats_day_uv_backup WHERE run_id = ?", r2.runId())).isZero();

		trackUpdate("UPDATE track_stats_day d SET uv = b.old_uv FROM track_stats_day_uv_backup b"
			+ " WHERE b.run_id = ? AND d.id = b.stats_day_id", r1.runId());
		assertThat(dayRow(app, day, "overview", "ALL").getLong("uv")).isEqualTo(1L);
	}

	@Test
	void onlyOneConcurrentRunAndLockIsRenewed() throws Exception {
		String app = "it-uv-lock";
		seedApp(app);
		for (int i = 1; i <= 5; i++) {
			seedDay(app, daysAgo(i));
		}
		statsDayJob.rollupNow();
		// TTL 1s、每天之间睡 400ms：整轮约 2s，超过 TTL，靠续期保持
		TrackUvBackfillJob a = job(props(1, 400, false));
		TrackUvBackfillJob b = job(props(1, 400, false));
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(3);
		try {
			List<Callable<TrackUvBackfillJob.Result>> calls = new ArrayList<>();
			calls.add(() -> { go.await(); return run(a, 10, app); });
			calls.add(() -> { go.await(); return run(b, 10, app); });
			List<Future<TrackUvBackfillJob.Result>> fs = new ArrayList<>();
			for (Callable<TrackUvBackfillJob.Result> c : calls) {
				fs.add(pool.submit(c));
			}
			// TTL 过期之后再来一份：锁若没续期就会被它抢到
			Future<TrackUvBackfillJob.Result> late = pool.submit(() -> { go.await(); Thread.sleep(1200); return run(b, 10, app); });
			go.countDown();
			List<TrackUvBackfillJob.Status> statuses = new ArrayList<>();
			for (Future<TrackUvBackfillJob.Result> f : fs) {
				statuses.add(f.get().status());
			}
			assertThat(statuses).containsExactlyInAnyOrder(TrackUvBackfillJob.Status.DONE, TrackUvBackfillJob.Status.LOCK_BUSY);
			assertThat(late.get().status()).as("续期后锁仍被持有").isEqualTo(TrackUvBackfillJob.Status.LOCK_BUSY);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void redisUnavailableDoesNotRun() {
		StringRedisTemplate broken = Mockito.mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked")
		ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
		Mockito.when(broken.opsForValue()).thenReturn(ops);
		Mockito.when(ops.setIfAbsent(Mockito.anyString(), Mockito.anyString(), Mockito.any(java.time.Duration.class)))
			.thenThrow(new RedisConnectionFailureException("down"));
		TrackUvBackfillJob j = new TrackUvBackfillJob(dataSource, broken, props(600, 0, false));
		assertThat(run(j, 5, "it-uv-none").status()).isEqualTo(TrackUvBackfillJob.Status.REDIS_UNAVAILABLE);
	}

	@Test
	void outsideWindowDoesNotRun() {
		TrackUvBackfillJob j = job(props(600, 0, true));
		// 北京时间 12:00 不在默认 02–07 时段
		TrackUvBackfillJobTestAccess.setClock(j, Clock.fixed(Instant.parse("2026-10-13T04:00:00Z"), ZoneOffset.UTC));
		assertThat(run(j, 5, "it-uv-none").status()).isEqualTo(TrackUvBackfillJob.Status.OUTSIDE_WINDOW);
		// 北京时间 03:00 在时段内
		TrackUvBackfillJobTestAccess.setClock(j, Clock.fixed(Instant.parse("2026-10-12T19:00:00Z"), ZoneOffset.UTC));
		assertThat(TrackUvBackfillJobTestAccess.inWindow(j)).isTrue();
	}

	@Test
	void resumesFromCursorInReverseOrder() {
		String app = "it-uv-resume";
		seedApp(app);
		LocalDate d1 = daysAgo(1);
		LocalDate d2 = daysAgo(2);
		LocalDate d3 = daysAgo(3);
		seedDay(app, d1);
		seedDay(app, d2);
		seedDay(app, d3);
		statsDayJob.rollupNow();
		setUv(app, d1, 99);
		setUv(app, d3, 99);
		// 模拟上次跑到 d2（倒序：d1、d2 已完成）后中断
		trackUpdate("INSERT INTO track_rollup_cursor (id, job_key, app_key, last_bucket, create_time, update_time)"
				+ " VALUES (?, 'uv_backfill', ?, ?, now(), now())"
				+ " ON CONFLICT (job_key, app_key) DO UPDATE SET last_bucket = EXCLUDED.last_bucket",
			IdUtil.getSnowflakeNextId(), app, d2.atStartOfDay());
		TrackUvBackfillJob.Result r = run(job(props(600, 0, false)), 10, app);
		assertThat(r.status()).isEqualTo(TrackUvBackfillJob.Status.DONE);
		assertThat(dayRow(app, d3, "overview", "ALL").getLong("uv")).as("续跑处理游标之前的日子").isEqualTo(2L);
		assertThat(dayRow(app, d1, "overview", "ALL").getLong("uv")).as("游标之后的已完成日不重复处理").isEqualTo(99L);
		// 游标已清：再跑一次从昨天开始
		run(job(props(600, 0, false)), 10, app);
		assertThat(dayRow(app, d1, "overview", "ALL").getLong("uv")).isEqualTo(2L);
	}

	@Test
	void timedOutAppDayIsSkippedWithoutFailingOthers() throws Exception {
		String slow = "it-uv-timeout-slow";
		String ok = "it-uv-timeout-ok";
		seedApp(slow);
		seedApp(ok);
		LocalDate d1 = daysAgo(1);
		LocalDate d2 = daysAgo(2);
		for (String app : List.of(slow, ok)) {
			seedDay(app, d1);
			seedDay(app, d2);
		}
		statsDayJob.rollupNow();
		setUv(slow, d1, 99);
		setUv(slow, d2, 99);
		setUv(ok, d1, 99);
		setUv(ok, d2, 99);
		TrackUvBackfillProperties p = props(600, 0, false);
		p.setAppStatementTimeout(Map.of(slow, Duration.ofMillis(1)));
		TrackUvBackfillJob j = job(p);
		// 另一事务锁住慢应用的日表行：1ms 超时必然在等锁/查询时触发 57014
		Connection holder = DataSourceKey.use(TrackConstants.DS_KEY, () -> {
			try {
				return dataSource.getConnection();
			} catch (java.sql.SQLException e) {
				throw new IllegalStateException(e);
			}
		});
		TrackUvBackfillJob.Result r;
		try {
			holder.setAutoCommit(false);
			try (Statement st = holder.createStatement()) {
				st.execute("SELECT id FROM track_stats_day WHERE app_key = '" + slow + "' FOR UPDATE");
			}
			r = run(j, 10, null);
		} finally {
			holder.rollback();
			holder.close();
		}
		assertThat(r.status()).as("整体不失败，但标未完成").isEqualTo(TrackUvBackfillJob.Status.PARTIAL);
		assertThat(r.completed()).isFalse();
		assertThat(r.failed()).contains(slow + "@" + d1 + "(57014)");
		assertThat(r.summary()).doesNotContain("UV 回填完成");
		assertThat(dayRow(slow, d1, "overview", "ALL").getLong("uv")).as("超时日已回滚").isEqualTo(99L);
		assertThat(dayRow(slow, d2, "overview", "ALL").getLong("uv")).as("该应用其余日子留到下次").isEqualTo(99L);
		assertThat(trackLong("SELECT count(*) AS c FROM track_rollup_cursor WHERE job_key = 'uv_backfill'"
			+ " AND app_key = ?", slow)).as("超时不推进游标").isZero();
		assertThat(dayRow(ok, d1, "overview", "ALL").getLong("uv")).as("其它应用照常推进").isEqualTo(2L);
		assertThat(dayRow(ok, d2, "overview", "ALL").getLong("uv")).isEqualTo(2L);

		// 放宽超时后重跑：慢应用补齐
		TrackUvBackfillJob.Result r2 = run(job(props(600, 0, false)), 10, slow);
		assertThat(r2.status()).isEqualTo(TrackUvBackfillJob.Status.DONE);
		assertThat(dayRow(slow, d1, "overview", "ALL").getLong("uv")).isEqualTo(2L);
		assertThat(dayRow(slow, d2, "overview", "ALL").getLong("uv")).isEqualTo(2L);
	}

	@Test
	void cursorAtFirstDayDoesNotRescan() {
		String app = "it-uv-cursor-first";
		seedApp(app);
		LocalDate d1 = daysAgo(1);
		LocalDate d2 = daysAgo(2);
		seedDay(app, d1);
		seedDay(app, d2);
		statsDayJob.rollupNow();
		setUv(app, d1, 99);
		// 上次已处理到最早一天（days=2 → first=d2）后丢锁，游标停在 first
		trackUpdate("INSERT INTO track_rollup_cursor (id, job_key, app_key, last_bucket, create_time, update_time)"
				+ " VALUES (?, 'uv_backfill', ?, ?, now(), now())"
				+ " ON CONFLICT (job_key, app_key) DO UPDATE SET last_bucket = EXCLUDED.last_bucket",
			IdUtil.getSnowflakeNextId(), app, d2.atStartOfDay());
		TrackUvBackfillJob.Result r = run(job(props(600, 0, false)), 2, app);
		assertThat(r.status()).isEqualTo(TrackUvBackfillJob.Status.DONE);
		assertThat(r.days()).as("不从头重扫").isZero();
		assertThat(dayRow(app, d1, "overview", "ALL").getLong("uv")).isEqualTo(99L);
		assertThat(trackLong("SELECT count(*) AS c FROM track_rollup_cursor WHERE job_key = 'uv_backfill'"
			+ " AND app_key = ?", app)).as("收尾清游标").isZero();
	}

	@Test
	void rejectsOutOfRangeDays() {
		assertThatThrownBy(() -> springJob.backfill(0, "x")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> springJob.backfill(5000, "x")).isInstanceOf(IllegalArgumentException.class);
	}
}
