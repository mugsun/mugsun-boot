package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * #76：跨 180° 要素的部分 GiST（{@code ST_ShiftLongitude(geom)}），供查询跨 180° 支走索引。
 * <p>{@code CREATE INDEX CONCURRENTLY} 不能在事务块内，故 {@link #canExecuteInTransaction()} 为 false。
 * 主库 Flyway 须用会话级锁（{@code flyway.postgresql.transactional.lock=false}），否则与 Flyway
 * 事务级 advisory 锁自等死锁——约定见 {@code FlywayPgCompatConfig}，与埋点库 T4 同口径。
 * <p>无 PostGIS / 无 {@code gis_feature} 时跳过，不让信创或无扩展环境启动失败。
 */
public class V17__gis_feature_wrap_index extends BaseJavaMigration {

	private static final Logger log = LoggerFactory.getLogger(V17__gis_feature_wrap_index.class);

	static final String INDEX_NAME = "idx_gis_feature_wrap";

	@Override
	public boolean canExecuteInTransaction() {
		return false;
	}

	@Override
	public Integer getChecksum() {
		return 17_000_001;
	}

	@Override
	public void migrate(Context context) throws Exception {
		Connection conn = context.getConnection();
		boolean restore = false;
		boolean prevAuto = conn.getAutoCommit();
		if (!prevAuto) {
			conn.setAutoCommit(true);
			restore = true;
		}
		try (Statement st = conn.createStatement()) {
			if (!hasPostgis(st)) {
				log.info("V17：无 PostGIS，跳过 {}", INDEX_NAME);
				return;
			}
			if (!tableExists(st, "gis_feature")) {
				log.info("V17：无 gis_feature，跳过 {}", INDEX_NAME);
				return;
			}
			if (!columnExists(st, "gis_feature", "wraps_antimeridian")) {
				throw new IllegalStateException("V17：gis_feature.wraps_antimeridian 不存在，请先执行 V16");
			}
			IndexState state = indexState(st, INDEX_NAME);
			if (state == IndexState.VALID) {
				log.info("V17：{} 已存在且 valid，跳过", INDEX_NAME);
				return;
			}
			if (state == IndexState.INVALID) {
				log.warn("V17：{} 为 invalid（上次 CONCURRENTLY 中断），DROP 后重建", INDEX_NAME);
				st.execute("DROP INDEX CONCURRENTLY IF EXISTS " + INDEX_NAME);
			}
			st.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + INDEX_NAME
				+ " ON gis_feature USING GIST (ST_ShiftLongitude(geom))"
				+ " WHERE wraps_antimeridian AND is_deleted = 0");
			if (indexState(st, INDEX_NAME) != IndexState.VALID) {
				throw new IllegalStateException("V17：创建 " + INDEX_NAME + " 后仍非 valid");
			}
			log.info("V17：已创建部分 GiST {}", INDEX_NAME);
		} finally {
			if (restore) {
				conn.setAutoCommit(prevAuto);
			}
		}
	}

	private static boolean hasPostgis(Statement st) throws SQLException {
		try (ResultSet rs = st.executeQuery("SELECT 1 FROM pg_extension WHERE extname = 'postgis'")) {
			return rs.next();
		}
	}

	private static boolean tableExists(Statement st, String table) throws SQLException {
		try (ResultSet rs = st.executeQuery(
			"SELECT 1 FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = '"
				+ table + "'")) {
			return rs.next();
		}
	}

	private static boolean columnExists(Statement st, String table, String column) throws SQLException {
		try (ResultSet rs = st.executeQuery(
			"SELECT 1 FROM information_schema.columns WHERE table_schema = current_schema()"
				+ " AND table_name = '" + table + "' AND column_name = '" + column + "'")) {
			return rs.next();
		}
	}

	private enum IndexState { MISSING, VALID, INVALID }

	private static IndexState indexState(Statement st, String name) throws SQLException {
		try (ResultSet rs = st.executeQuery(
			"SELECT i.indisvalid FROM pg_class c JOIN pg_index i ON i.indexrelid = c.oid"
				+ " WHERE c.relname = '" + name + "'")) {
			if (!rs.next()) {
				return IndexState.MISSING;
			}
			return rs.getBoolean(1) ? IndexState.VALID : IndexState.INVALID;
		}
	}
}
