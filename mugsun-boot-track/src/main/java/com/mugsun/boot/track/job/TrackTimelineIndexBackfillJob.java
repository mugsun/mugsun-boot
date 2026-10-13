package com.mugsun.boot.track.job;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.track.TrackDS;
import com.mugsun.boot.track.job.TrackTimelineIndexes.IndexSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 时间线 (ts, id) 索引后台补建：给 T4 跳过的存量非空分区逐个 CREATE INDEX CONCURRENTLY → 校验 indisvalid → ATTACH。
 * <ul>
 *   <li><b>调度</b>：复用 {@link TrackPartitionJob} 的每小时 tick（{@link #runIfDue()}），提交到本类单线程执行器，
 *   不占用 Spring 调度线程；同一时刻本节点最多一轮。</li>
 *   <li><b>开关</b>：mugsun.track.timeline-index.backfill-enabled（默认 true）。</li>
 *   <li><b>低峰</b>：只在 window-start..window-end（默认北京时间 02:00–06:00）内<b>开始</b>新分区；单个分区建到一半不中断。</li>
 *   <li><b>互斥</b>：与 T4、TrackPartitionJob 共用 {@code pg_advisory_lock(hashtext('track_timeline_index'))}（会话级，
 *   不支持 PgBouncer transaction pooling）；取不到即跳过（多节点只有一个在跑）。</li>
 *   <li><b>进度</b>：每完成一个分区写 track_rollup_cursor（job_key={@value #JOB_KEY}，app_key=分区名，last_bucket=完成时间）；
 *   未完成的分区下次 tick 从 pg_index 现状续上（中断留下的 invalid 子索引会先 DROP 再重建）。</li>
 * </ul>
 */
@Component
@TrackDS
public class TrackTimelineIndexBackfillJob {

	private static final Logger log = LoggerFactory.getLogger(TrackTimelineIndexBackfillJob.class);

	/** 进度表 job_key */
	public static final String JOB_KEY = "timeline_index";

	/** 一轮结果 */
	public enum Status { DISABLED, OUTSIDE_WINDOW, ALREADY_RUNNING, LOCK_BUSY, NOTHING_TO_DO, DONE, PAUSED_WINDOW, PARTIAL }

	public record Result(Status status, List<String> built, List<String> failed) {
		public Result {
			built = built == null ? List.of() : List.copyOf(built);
			failed = failed == null ? List.of() : List.copyOf(failed);
		}
	}

	private final JdbcTemplate jdbc;
	private final TrackTimelineIndexProperties props;
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "track-timeline-index-backfill");
		t.setDaemon(true);
		return t;
	});
	private volatile Clock clock = Clock.systemUTC();

	public TrackTimelineIndexBackfillJob(DataSource dataSource, TrackTimelineIndexProperties props) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.props = props;
	}

	/** 测试用：固定时钟 */
	public void setClock(Clock clock) {
		this.clock = clock;
	}

	/** 由 TrackPartitionJob 每小时 tick 调用：开关/时段满足则异步跑一轮（不阻塞调度线程） */
	public void runIfDue() {
		if (!props.isBackfillEnabled() || !inWindow() || running.get()) {
			return;
		}
		executor.submit(() -> {
			try {
				runNow(TrackTimelineIndexes.TIMELINE);
			} catch (RuntimeException e) {
				log.warn("时间线索引后台补建异常（下次 tick 重试）：{}", e.getMessage());
			}
		});
	}

	/** 同步跑一轮（测试/手动触发用）；specs 默认 {@link TrackTimelineIndexes#TIMELINE} */
	public Result runNow(List<IndexSpec> specs) {
		if (!props.isBackfillEnabled()) {
			return new Result(Status.DISABLED, null, null);
		}
		if (!inWindow()) {
			return new Result(Status.OUTSIDE_WINDOW, null, null);
		}
		if (!running.compareAndSet(false, true)) {
			return new Result(Status.ALREADY_RUNNING, null, null);
		}
		try {
			Result r = jdbc.execute((ConnectionCallback<Result>) conn -> backfill(conn, specs));
			if (r.status() != Status.NOTHING_TO_DO) {
				log.info("时间线索引后台补建：{}，新建 {}，失败 {}", r.status(), r.built(), r.failed());
			}
			return r;
		} finally {
			running.set(false);
		}
	}

	private Result backfill(Connection conn, List<IndexSpec> specs) throws SQLException {
		if (!conn.getAutoCommit()) {
			conn.setAutoCommit(true);
		}
		if (!TrackTimelineIndexes.tryLock(conn)) {
			return new Result(Status.LOCK_BUSY, null, null);
		}
		List<String> built = new ArrayList<>();
		List<String> failed = new ArrayList<>();
		try {
			List<String> todo = new ArrayList<>();
			for (String partition : TrackTimelineIndexes.partitions(conn)) {
				if (!TrackTimelineIndexes.partitionReady(conn, partition, specs)) {
					todo.add(partition);
				}
			}
			if (todo.isEmpty()) {
				return new Result(Status.NOTHING_TO_DO, null, null);
			}
			for (int i = 0; i < todo.size(); i++) {
				String partition = todo.get(i);
				if (i > 0) {
					if (!inWindow()) {
						return new Result(Status.PAUSED_WINDOW, built, failed);
					}
					sleep(props.getPauseMillis());
				}
				try {
					long t0 = System.currentTimeMillis();
					TrackTimelineIndexes.ensurePartition(conn, partition, specs, false);
					recordProgress(conn, partition);
					built.add(partition);
					log.info("时间线索引后台补建：分区 {} 完成（{} ms）", partition, System.currentTimeMillis() - t0);
				} catch (SQLException e) {
					failed.add(partition);
					log.warn("时间线索引后台补建：分区 {} 失败，下次重试：{}", partition, e.getMessage());
				}
			}
		} finally {
			TrackTimelineIndexes.unlock(conn);
		}
		return new Result(failed.isEmpty() ? Status.DONE : Status.PARTIAL, built, failed);
	}

	/** 进度：每完成一个分区 upsert 一行 */
	private void recordProgress(Connection conn, String partition) throws SQLException {
		Timestamp now = Timestamp.from(clock.instant());
		try (PreparedStatement ps = conn.prepareStatement(
			"INSERT INTO track_rollup_cursor (id, job_key, app_key, last_bucket, create_time, update_time)"
				+ " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (job_key, app_key)"
				+ " DO UPDATE SET last_bucket = EXCLUDED.last_bucket, update_time = EXCLUDED.update_time")) {
			ps.setLong(1, IdUtil.getSnowflakeNextId());
			ps.setString(2, JOB_KEY);
			ps.setString(3, partition);
			ps.setTimestamp(4, now);
			ps.setTimestamp(5, now);
			ps.setTimestamp(6, now);
			ps.executeUpdate();
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

	private static void sleep(long ms) {
		if (ms <= 0) {
			return;
		}
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
