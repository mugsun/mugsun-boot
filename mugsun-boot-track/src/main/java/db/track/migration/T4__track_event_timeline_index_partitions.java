package db.track.migration;

import com.mugsun.boot.track.job.TrackTimelineIndexes;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.migration.Context;
import org.flywaydb.core.api.migration.JavaMigration;

import java.sql.Connection;

/**
 * 埋点库 T4（非事务 Java 迁移）：T3 只在 track_event 父表建了 ON ONLY 索引（invalid），
 * 这里逐分区 CREATE INDEX CONCURRENTLY（不阻塞写入）→ ATTACH PARTITION，分区间停顿 {@value #PAUSE_MS}ms 限速；
 * 挂齐后父索引自动转 valid，结束时校验父/子 indisvalid，不全 valid 即失败（Flyway 记失败，修复后可重跑，逻辑幂等）。
 * <p>实现 {@link JavaMigration} 而非继承 BaseJavaMigration：后者按类名解析版本只认 V 前缀，埋点库序列为 T。
 */
public class T4__track_event_timeline_index_partitions implements JavaMigration {

	/** 分区间限速停顿 */
	static final long PAUSE_MS = 500L;

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
		return 4_000_001;
	}

	@Override
	public boolean canExecuteInTransaction() {
		return false; // CREATE INDEX CONCURRENTLY 不能在事务块内
	}

	@Override
	public void migrate(Context context) throws Exception {
		Connection conn = context.getConnection();
		if (!conn.getAutoCommit()) {
			conn.setAutoCommit(true);
		}
		TrackTimelineIndexes.ensureAll(conn, PAUSE_MS);
		if (!TrackTimelineIndexes.allValid(conn)) {
			throw new IllegalStateException("时间线分区索引未全部 valid（pg_index.indisvalid），请检查后重跑迁移");
		}
	}
}
