package db.track.migration;

import com.mugsun.boot.track.job.TrackTimelineIndexes;
import com.mugsun.boot.track.job.TrackTimelineIndexes.IndexSpec;
import com.mugsun.boot.track.job.TrackTimelineIndexes.Outcome;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * 埋点库 T4（非事务 Java 迁移）：T3 只在 track_event 父表建了 ON ONLY 索引（invalid）。
 * <p><b>启动期绝不同步建大分区索引</b>：本迁移只给<b>空分区</b>（次月预建/default 等）CONCURRENTLY 建并挂接，
 * 非空存量分区跳过，留给 {@code TrackTimelineIndexBackfillJob} 在低峰期后台补（开关 mugsun.track.timeline-index.backfill-enabled）。
 * 本迁移<b>永不抛异常</b>：取不到 advisory 锁、建索引出错、结束后仍有分区未就绪，一律 WARN 后照常记成功，启动不受影响。
 * 父索引在全部分区挂齐之前保持 invalid；查询按分区选索引——已挂好的分区直接用新索引，未补的分区退回 T1 旧索引，功能不受影响。
 * <p>实现 {@link JavaMigration} 而非继承 BaseJavaMigration：后者按类名解析版本只认 V 前缀，埋点库序列为 T。
 */
public class T4__track_event_timeline_index_partitions implements JavaMigration {

	private static final Logger log = LoggerFactory.getLogger(T4__track_event_timeline_index_partitions.class);

	@Override
	public MigrationVersion getVersion() {
		return MigrationVersion.fromVersion("4");
	}

	@Override
	public String getDescription() {
		return "track event timeline index partitions";
	}

	@Override
	public Integer getChecksum() {
		return 4_000_002;
	}

	@Override
	public boolean canExecuteInTransaction() {
		return false; // CREATE INDEX CONCURRENTLY 不能在事务块内
	}

	@Override
	public void migrate(Context context) {
		run(context.getConnection(), TrackTimelineIndexes.TIMELINE);
	}

	/** 迁移主体（public 供集成测试用自定义索引规格验证）；返回仍待后台补的分区，永不抛异常 */
	public static List<String> run(Connection conn, List<IndexSpec> specs) {
		List<String> pending = new ArrayList<>();
		try {
			if (!conn.getAutoCommit()) {
				conn.setAutoCommit(true);
			}
			if (!TrackTimelineIndexes.tryLock(conn)) {
				log.warn("T4：时间线索引锁被占用（后台补建或分区任务执行中），本次跳过；由后台任务补齐");
				return pending;
			}
			try {
				for (String partition : TrackTimelineIndexes.partitions(conn)) {
					try {
						if (TrackTimelineIndexes.ensurePartition(conn, partition, specs, true) == Outcome.SKIPPED_NOT_EMPTY) {
							pending.add(partition);
						}
					} catch (Exception e) {
						pending.add(partition);
						log.warn("T4：分区 {} 时间线索引处理失败，留给后台任务：{}", partition, e.getMessage());
					}
				}
			} finally {
				TrackTimelineIndexes.unlock(conn);
			}
			if (!pending.isEmpty()) {
				log.warn("T4：{} 个非空分区未建时间线索引（启动期不同步建），将由后台任务在低峰期补齐："
					+ "mugsun.track.timeline-index.backfill-enabled=true；分区：{}", pending.size(), pending);
			} else if (!TrackTimelineIndexes.allValid(conn, specs)) {
				log.warn("T4：时间线索引未全部 valid（pg_index.indisvalid），由后台任务继续校验补齐");
			}
		} catch (Exception e) {
			log.warn("T4：时间线索引校验异常，已跳过（不影响启动），由后台任务补齐：{}", e.getMessage());
		}
		return pending;
	}
}
