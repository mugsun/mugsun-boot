package com.mugsun.boot.track.job;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.TrackDS;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 历史 track_stats_day UV 回填：按 {@link TrackConstants#UV_EXPR}（distinct_id 口径）从明细重算已有日表行的 uv。
 * <ul>
 *   <li><b>幂等</b>：只改 uv 与重算值不同的行；重复执行第二遍改动 0 行、也不再写备份。</li>
 *   <li><b>先备份</b>：改动前旧值写入 {@code track_stats_day_uv_backup}（run_id 可整批还原），与更新同一条语句完成。</li>
 *   <li><b>单遍重算</b>：一条 GROUPING SETS 查询同时算 overview 与四个维度的 UV（只扫一次当日明细）。</li>
 *   <li><b>分批限速</b>：一个事务只处理一个 app×天（事务内 {@code SET LOCAL statement_timeout}），
 *       每个 app×天 之间 sleep；默认只在北京时间 02:00–07:00 执行，出了时段就停、下次续跑。</li>
 *   <li><b>续跑</b>：日期从昨天往前倒序，进度写 {@code track_rollup_cursor}（job_key=uv_backfill，last_bucket=最近完成的日），
 *       一个应用全部完成后删掉游标，下次从头开始（幂等，空跑）。</li>
 *   <li><b>独立锁</b>：Redis 长 TTL 锁，每个 app×天 处理完用 token 校验的 Lua 续期；Redis 不可用时不执行；续期失败（锁丢了）立即停。</li>
 *   <li><b>单日失败不卡死</b>：某个 app×天 出现数据库错误（超时 57014、死锁 40P01、锁等待 55P03、连接 08xxx 等，
 *       即异常链里有 SQLException）时回滚该天、记 WARN 日志并把 {@code app@日期(SQLState)} 计入结果的失败清单，
 *       不推进该应用游标，跳过该应用其余日子，继续下一个应用；结果为 PARTIAL（未完成）。非 SQL 的程序错误仍抛出。
 *       可按 appKey 单独放宽超时（配置键用中括号，见 {@link TrackUvBackfillProperties#getAppStatementTimeout()}）。</li>
 *   <li>只改 uv 列，不新增行；明细已清理（当日无事件）的日子跳过，不会清零。</li>
 * </ul>
 */
@Component
@TrackDS
public class TrackUvBackfillJob extends AbstractTrackRollupJob {

	static final String JOB_KEY = "uv_backfill";
	static final String LOCK_UV_BACKFILL = TrackConstants.REDIS_PREFIX + "lock:uv-backfill";
	static final int MAX_DAYS = 3660;
	/** PostgreSQL query_canceled（statement_timeout 触发） */
	static final String SQLSTATE_QUERY_CANCELED = "57014";
	private static final Pattern WORK_MEM = Pattern.compile("^[1-9][0-9]{0,6}(kB|MB|GB)$");

	private static final String RENEW_SCRIPT = "if redis.call('get', KEYS[1]) == ARGV[1] then"
		+ " return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end";
	private static final String RELEASE_SCRIPT =
		"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";

	private static final String PAGE_EXPR = "left(coalesce(nullif(e.route_path, ''), e.url_path), "
		+ TrackConstants.DIM_MAX_LEN + ")";

	/**
	 * 单遍 UV：GROUPING SETS 一次扫描出 overview（()）与 event/page/referrer/device 四维 UV。
	 * g 位：event=8 page=4 referrer=2 device=1（被汇总的列置 1）。空维度值在 Java 侧按日表口径过滤。
	 */
	static final String UV_SQL = "SELECT GROUPING(e.event_name, " + PAGE_EXPR + ", e.referrer_domain, e.device) AS g,"
		+ " e.event_name AS ev, " + PAGE_EXPR + " AS pg, e.referrer_domain AS rf, e.device AS dv,"
		+ " " + TrackConstants.UV_EXPR + " AS uv, count(*) AS cnt"
		+ " FROM track_event e WHERE e.app_key = ? AND e.received_at >= ? AND e.received_at < ?"
		+ " GROUP BY GROUPING SETS ((), (e.event_name), (" + PAGE_EXPR + "), (e.referrer_domain), (e.device))";

	/** 单 app×天：备份变化行旧值 + 覆盖 uv（原子）。参数：dim_type[], dim_key[], uv[], app_key, stat_date, run_id */
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
		+ " SELECT (SELECT count(*) FROM upd) AS updated";

	private final StringRedisTemplate redis;
	private final TrackUvBackfillProperties props;
	private Clock clock = Clock.systemUTC();

	public TrackUvBackfillJob(DataSource dataSource, StringRedisTemplate redis, TrackUvBackfillProperties props) {
		super(dataSource);
		this.redis = redis;
		this.props = props;
	}

	void setClock(Clock clock) {
		this.clock = clock;
	}

	/** 结束原因 */
	public enum Status { DONE, PARTIAL, LOCK_BUSY, REDIS_UNAVAILABLE, OUTSIDE_WINDOW, PAUSED_WINDOW, LOCK_LOST }

	/**
	 * 回填结果；noStatsDays=日表本无行的日子不计入（只处理有日表行的日子），noDetailDays=有日表行但明细已清理；
	 * failed=因数据库错误回滚、未推进游标的 app×天（"appKey@yyyy-MM-dd(SQLState)"）。
	 */
	public record Result(Status status, String runId, int apps, int days, int noDetailDays, long updatedRows,
						 List<String> failed) {
		public Result {
			failed = failed == null ? List.of() : List.copyOf(failed);
		}

		public boolean locked() {
			return status != Status.LOCK_BUSY && status != Status.REDIS_UNAVAILABLE;
		}

		/** 只有 DONE 才算完成；窗外、暂停、锁丢失、部分超时都是未完成（调度结果标失败，下次续跑） */
		public boolean completed() {
			return status == Status.DONE;
		}

		public String summary() {
			return switch (status) {
				case LOCK_BUSY -> "UV 回填未获锁（其它节点执行中），跳过";
				case REDIS_UNAVAILABLE -> "UV 回填需要 Redis 锁，Redis 不可用，本次不执行";
				case OUTSIDE_WINDOW -> "当前不在 UV 回填运行时段，本次不执行";
				default -> (switch (status) {
					case DONE -> "UV 回填完成";
					case PARTIAL -> "UV 回填未完成（部分 应用×天 失败，已跳过、下次重试：" + String.join(",", failed) + "）";
					case PAUSED_WINDOW -> "UV 回填已到运行时段末尾，暂停（下次续跑）";
					default -> "UV 回填锁已失效，停止（下次续跑）";
				})
					+ "：run_id=" + runId + "，应用 " + apps + " 个，重算 " + days + " 天（有日表行但明细已清理、保留原值 "
					+ noDetailDays + " 天），改动 " + updatedRows + " 行（旧值已备份到 track_stats_day_uv_backup）";
			};
		}
	}

	public int defaultDays() {
		return props.getDays();
	}

	public Result backfill() {
		return backfill(props.getDays(), null);
	}

	/**
	 * @param days   回填最近多少个闭合日（1..3660）
	 * @param appKey 只回填该应用；null/空表示全部应用
	 */
	public Result backfill(int days, String appKey) {
		if (days < 1 || days > MAX_DAYS) {
			throw new IllegalArgumentException("回填天数需在 1 到 " + MAX_DAYS + " 之间");
		}
		if (!inWindow()) {
			return new Result(Status.OUTSIDE_WINDOW, null, 0, 0, 0, 0, List.of());
		}
		String token = IdUtil.fastSimpleUUID();
		Boolean acquired;
		try {
			acquired = redis.opsForValue().setIfAbsent(LOCK_UV_BACKFILL, token, Duration.ofSeconds(props.getLockTtlSeconds()));
		} catch (RuntimeException e) {
			log.warn("UV 回填需要 Redis 锁，Redis 不可用，本次不执行：{}", e.getMessage());
			return new Result(Status.REDIS_UNAVAILABLE, null, 0, 0, 0, 0, List.of());
		}
		if (!Boolean.TRUE.equals(acquired)) {
			return new Result(Status.LOCK_BUSY, null, 0, 0, 0, 0, List.of());
		}
		try {
			Result r = doBackfill(days, appKey, token);
			log.info(r.summary());
			return r;
		} finally {
			try {
				redis.execute(new DefaultRedisScript<>(RELEASE_SCRIPT, Long.class), List.of(LOCK_UV_BACKFILL), token);
			} catch (RuntimeException e) {
				log.warn("UV 回填锁释放失败（等 TTL 过期）：{}", e.getMessage());
			}
		}
	}

	boolean inWindow() {
		if (!props.isWindowEnabled()) {
			return true;
		}
		LocalTime now = clock.instant().atZone(ZoneId.of(props.getWindowZone())).toLocalTime();
		LocalTime s = props.windowStartTime();
		LocalTime e = props.windowEndTime();
		return s.isBefore(e) ? !now.isBefore(s) && now.isBefore(e) : !now.isBefore(s) || now.isBefore(e);
	}

	private boolean renew(String token) {
		try {
			Long r = redis.execute(new DefaultRedisScript<>(RENEW_SCRIPT, Long.class), List.of(LOCK_UV_BACKFILL),
				token, String.valueOf(props.getLockTtlSeconds() * 1000));
			return r != null && r == 1L;
		} catch (RuntimeException e) {
			log.warn("UV 回填锁续期失败：{}", e.getMessage());
			return false;
		}
	}

	private Result doBackfill(int days, String appKey, String token) {
		String runId = "uvbf-" + OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
			.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
			+ "-" + Long.toHexString(System.nanoTime() & 0xffffL);
		LocalDate yesterday = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(1);
		LocalDate first = yesterday.minusDays(days - 1L);
		List<AppScope> apps = listApps().stream()
			.filter(a -> appKey == null || appKey.isBlank() || appKey.equals(a.appKey()))
			.toList();
		warnUnknownTimeoutKeys(listApps());
		int processed = 0;
		int noDetail = 0;
		long updated = 0;
		List<String> failed = new ArrayList<>();
		appLoop:
		for (AppScope app : apps) {
			LocalDate cursor = readCursorDate(app.appKey());
			// 游标 = 上次已完成到的日（倒序推进）；落在本次范围内则从它的前一天续跑
			// 游标 == first 表示最早一天已完成（例如完成后丢锁、没来得及删游标）：start 落到 first 之前，查不到日子，直接收尾
			LocalDate start = cursor != null && !cursor.isAfter(yesterday) && !cursor.isBefore(first)
				? cursor.minusDays(1) : yesterday;
			// 一次查出范围内有日表行的日子（倒序）；没有日表行的日子不需要回填
			List<LocalDate> statDays = jdbc.query("SELECT DISTINCT stat_date FROM track_stats_day"
					+ " WHERE app_key = ? AND stat_date >= ? AND stat_date <= ? AND is_deleted = 0 ORDER BY stat_date DESC",
				(rs, i) -> rs.getObject(1, LocalDate.class), app.appKey(), first, start);
			for (LocalDate day : statDays) {
				if (!inWindow()) {
					return new Result(Status.PAUSED_WINDOW, runId, apps.size(), processed, noDetail, updated, failed);
				}
				Long n;
				try {
					n = backfillDay(app.appKey(), day, runId);
				} catch (RuntimeException e) {
					String state = sqlStateOf(e);
					if (state == null) {
						throw e; // 非 SQL 的程序错误：不吞
					}
					// 单个 app×天 数据库错误：已回滚；不推进游标（下次从这天重试），跳过该应用其余日子，继续下一个应用
					failed.add(app.appKey() + "@" + day + "(" + state + ")");
					if (SQLSTATE_QUERY_CANCELED.equals(state)) {
						log.warn("UV 回填超时（SQLState 57014，statement_timeout={}ms）已回滚并跳过：app={} day={} run_id={}；"
								+ "游标未推进，下次从这天重试。同一 app@日期 连续超时请单独放宽："
								+ "mugsun.track.uv-backfill.app-statement-timeout.\"[{}]\"",
							statementTimeoutMillis(app.appKey()), app.appKey(), day, runId, app.appKey());
					} else {
						log.warn("UV 回填数据库错误（SQLState {}）已回滚并跳过：app={} day={} run_id={}；游标未推进，下次从这天重试：{}",
							state, app.appKey(), day, runId, rootMessage(e));
					}
					if (!renew(token)) {
						return new Result(Status.LOCK_LOST, runId, apps.size(), processed, noDetail, updated, failed);
					}
					continue appLoop;
				}
				if (n == null) {
					noDetail++;
				} else {
					processed++;
					updated += n;
				}
				advanceCursor(app.appKey(), day.atStartOfDay());
				if (!renew(token)) {
					return new Result(Status.LOCK_LOST, runId, apps.size(), processed, noDetail, updated, failed);
				}
				sleep();
			}
			jdbc.update("DELETE FROM track_rollup_cursor WHERE job_key = ? AND app_key = ?", JOB_KEY, app.appKey());
		}
		return new Result(failed.isEmpty() ? Status.DONE : Status.PARTIAL, runId, apps.size(), processed, noDetail,
			updated, failed);
	}

	/** 异常链里第一个 SQLException 的 SQLState（无 SQLState 记 UNKNOWN）；不是数据库错误返回 null */
	static String sqlStateOf(Throwable e) {
		int guard = 0;
		for (Throwable t = e; t != null && guard++ < 32; t = t.getCause()) {
			if (t instanceof SQLException se) {
				return se.getSQLState() == null || se.getSQLState().isBlank() ? "UNKNOWN" : se.getSQLState();
			}
		}
		return null;
	}

	private static String rootMessage(Throwable e) {
		Throwable t = e;
		while (t.getCause() != null && t.getCause() != t) {
			t = t.getCause();
		}
		return t.getMessage();
	}

	/** 启动时与每次执行前：超时覆盖里对不上现有应用的键打 WARN（多半是没加中括号被小写化 / 去掉了 . _） */
	@org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
	public void checkTimeoutOverridesOnStartup() {
		try {
			warnUnknownTimeoutKeys(listApps());
		} catch (RuntimeException e) {
			log.debug("UV 回填超时覆盖检查跳过：{}", e.getMessage());
		}
	}

	List<String> warnUnknownTimeoutKeys(List<AppScope> apps) {
		Map<String, Duration> overrides = props.getAppStatementTimeout();
		if (overrides == null || overrides.isEmpty()) {
			return List.of();
		}
		java.util.Set<String> keys = new java.util.HashSet<>();
		apps.forEach(a -> keys.add(a.appKey()));
		List<String> unknown = overrides.keySet().stream().filter(k -> !keys.contains(k)).sorted().toList();
		if (!unknown.isEmpty()) {
			log.warn("mugsun.track.uv-backfill.app-statement-timeout 中的键对不上现有应用：{}。"
				+ "appKey 含大写或 . _ 等字符时键必须加中括号并加引号，如 \"[MyApp_1]\": 600s", unknown);
		}
		return unknown;
	}

	long statementTimeoutMillis(String appKey) {
		Map<String, Duration> overrides = props.getAppStatementTimeout();
		Duration d = overrides == null ? null : overrides.get(appKey);
		long ms = d != null ? d.toMillis() : props.getStatementTimeoutSeconds() * 1000L;
		return Math.max(1L, ms);
	}

	private LocalDate readCursorDate(String appKey) {
		return readCursor(appKey) == null ? null : readCursor(appKey).toLocalDate();
	}

	private void sleep() {
		if (props.getSleepMillis() <= 0) {
			return;
		}
		try {
			Thread.sleep(props.getSleepMillis());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** 单应用单日（一个事务，statement_timeout 限时）：返回改动行数；当日明细为空返回 null */
	Long backfillDay(String appKey, LocalDate day, String runId) {
		OffsetDateTime from = day.atStartOfDay().atOffset(ZoneOffset.UTC);
		OffsetDateTime to = from.plusDays(1);
		return jdbc.execute((Connection c) -> {
			boolean auto = c.getAutoCommit();
			c.setAutoCommit(false);
			try {
				try (Statement st = c.createStatement()) {
					st.execute("SET LOCAL statement_timeout = " + statementTimeoutMillis(appKey));
					String wm = props.getWorkMem();
					if (wm != null && !wm.isBlank()) {
						if (!WORK_MEM.matcher(wm).matches()) {
							throw new IllegalStateException("mugsun.track.uv-backfill.work-mem 格式应为如 256MB：" + wm);
						}
						st.execute("SET LOCAL work_mem = '" + wm + "'");
					}
				}
				List<String> types = new ArrayList<>();
				List<String> keys = new ArrayList<>();
				List<Long> uvs = new ArrayList<>();
				long cnt = 0;
				try (PreparedStatement ps = c.prepareStatement(UV_SQL)) {
					ps.setString(1, appKey);
					ps.setObject(2, from);
					ps.setObject(3, to);
					try (ResultSet rs = ps.executeQuery()) {
						while (rs.next()) {
							int g = rs.getInt("g");
							long uv = rs.getLong("uv");
							String type;
							String key;
							switch (g) {
								case 15 -> {
									cnt = rs.getLong("cnt");
									type = TrackConstants.DIM_OVERVIEW;
									key = TrackConstants.DIM_KEY_ALL;
								}
								case 7 -> {
									type = TrackConstants.DIM_EVENT;
									key = rs.getString("ev");
								}
								case 11 -> {
									type = TrackConstants.DIM_PAGE;
									key = rs.getString("pg");
								}
								case 13 -> {
									type = TrackConstants.DIM_REFERRER;
									key = rs.getString("rf");
								}
								case 14 -> {
									type = TrackConstants.DIM_DEVICE;
									key = rs.getString("dv");
								}
								default -> {
									continue;
								}
							}
							// 与日表维度口径一致：page 空、referrer/device 空串或 NULL 不成行
							if (key == null || (key.isEmpty() && (TrackConstants.DIM_REFERRER.equals(type)
								|| TrackConstants.DIM_DEVICE.equals(type)))) {
								continue;
							}
							types.add(type);
							keys.add(key);
							uvs.add(uv);
						}
					}
				}
				if (cnt == 0) {
					c.commit();
					return null;
				}
				long updated;
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
						updated = rs.next() ? rs.getLong("updated") : 0L;
					}
				}
				c.commit();
				return updated;
			} catch (SQLException | RuntimeException e) {
				try {
					c.rollback();
				} catch (SQLException re) {
					e.addSuppressed(re);
				}
				throw e;
			} finally {
				c.setAutoCommit(auto);
			}
		});
	}

	@Override
	protected String jobKey() {
		return JOB_KEY;
	}

	@Override
	protected void aggregateWindow(AppScope app, LocalDateTime bucketStart) {
		throw new UnsupportedOperationException("UV 回填不走窗口补扫");
	}
}
