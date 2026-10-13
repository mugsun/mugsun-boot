package com.mugsun.boot.track.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 时间线 (ts, id) 分区索引维护（T3 父表 ON ONLY 索引的分区侧）：逐分区
 * {@code CREATE INDEX CONCURRENTLY} → 校验 indisvalid → {@code ALTER INDEX parent ATTACH PARTITION child}。
 * <p>三个调用方共用一把 PG 会话级 advisory 锁 {@code pg_advisory_lock(hashtext('track_timeline_index'))}：
 * T4 迁移（只处理空分区）、{@link TrackTimelineIndexBackfillJob}（低峰期补存量大分区）、{@link TrackPartitionJob}（新分区校验）。
 * 会话级锁要求连接直连 PG 或 PgBouncer session 模式——<b>不支持 PgBouncer transaction pooling</b>。
 * <p>调用方须给 <b>autocommit=true</b> 的连接（CONCURRENTLY 不能在事务块内），并在同一连接上加/解锁。
 */
public final class TrackTimelineIndexes {

	private static final Logger log = LoggerFactory.getLogger(TrackTimelineIndexes.class);

	/** 父表 */
	public static final String PARENT_TABLE = "track_event";
	/** advisory 锁键（hashtext 入参） */
	public static final String LOCK_KEY = "track_timeline_index";
	/** 父索引名前缀（子索引名 = 分区名 + "_" + 去前缀父索引名） */
	private static final String PARENT_PREFIX = "idx_event_";
	/** 标识符白名单（拼 SQL 前校验） */
	private static final Pattern SAFE_IDENT = Pattern.compile("^[a-z0-9_]{1,63}$");
	private static final Pattern SAFE_COLS = Pattern.compile("^[a-z0-9_, ]{1,200}$");

	/** 父索引规格 */
	public record IndexSpec(String parentIndex, String columns) {
		public IndexSpec {
			if (!parentIndex.startsWith(PARENT_PREFIX) || !SAFE_IDENT.matcher(parentIndex).matches()
				|| !SAFE_COLS.matcher(columns).matches()) {
				throw new IllegalArgumentException("非法索引规格：" + parentIndex + " (" + columns + ")");
			}
		}
	}

	/** T3 的两条父索引 */
	public static final List<IndexSpec> TIMELINE = List.of(
		new IndexSpec("idx_event_user_ts_timeline", "app_key, user_id, ts, id"),
		new IndexSpec("idx_event_distinct_ts_timeline", "app_key, distinct_id, ts, id"));

	/** 单分区处理结果 */
	public enum Outcome { ATTACHED, BUILT, SKIPPED_NOT_EMPTY, NO_PARENT }

	private TrackTimelineIndexes() {
	}

	static String childIndexName(String partition, String parentIndex) {
		return partition + "_" + parentIndex.substring(PARENT_PREFIX.length());
	}

	/** 尝试取会话级 advisory 锁（非阻塞） */
	public static boolean tryLock(Connection conn) throws SQLException {
		try (Statement st = conn.createStatement();
			 ResultSet rs = st.executeQuery("SELECT pg_try_advisory_lock(hashtext('" + LOCK_KEY + "'))")) {
			return rs.next() && rs.getBoolean(1);
		}
	}

	/** 释放会话级 advisory 锁（吞异常，只记日志） */
	public static void unlock(Connection conn) {
		try (Statement st = conn.createStatement()) {
			st.execute("SELECT pg_advisory_unlock(hashtext('" + LOCK_KEY + "'))");
		} catch (SQLException e) {
			log.warn("时间线索引锁释放失败（连接关闭时随会话释放）：{}", e.getMessage());
		}
	}

	/** 父表直接分区（新月份在前） */
	public static List<String> partitions(Connection conn) throws SQLException {
		List<String> out = new ArrayList<>();
		try (PreparedStatement ps = conn.prepareStatement(
			"SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid"
				+ " WHERE i.inhparent = ?::regclass ORDER BY c.relname DESC")) {
			ps.setString(1, PARENT_TABLE);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(rs.getString(1));
				}
			}
		}
		return out;
	}

	/** 分区是否为空（读一行即止，不扫全表） */
	public static boolean isEmpty(Connection conn, String partition) throws SQLException {
		checkIdent(partition);
		try (Statement st = conn.createStatement();
			 ResultSet rs = st.executeQuery("SELECT 1 FROM \"" + partition + "\" LIMIT 1")) {
			return !rs.next();
		}
	}

	/** 该分区在全部 specs 上都已挂接 valid 子索引 */
	public static boolean partitionReady(Connection conn, String partition, List<IndexSpec> specs) throws SQLException {
		for (IndexSpec spec : specs) {
			String child = attachedChild(conn, spec.parentIndex(), partition);
			if (child == null || !Boolean.TRUE.equals(indexValid(conn, child))) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 单分区补齐（调用方已持锁）：已挂接即跳过；onlyIfEmpty 且分区非空 → SKIPPED_NOT_EMPTY（留给后台任务）；
	 * 否则 CONCURRENTLY 建（invalid 残留先 DROP 重建）→ 校验 indisvalid → ATTACH。返回本分区最"重"的结果。
	 */
	public static Outcome ensurePartition(Connection conn, String partition, List<IndexSpec> specs, boolean onlyIfEmpty)
		throws SQLException {
		checkIdent(partition);
		Outcome result = Outcome.ATTACHED;
		Boolean empty = null;
		for (IndexSpec spec : specs) {
			String parentIndex = spec.parentIndex();
			if (indexValid(conn, parentIndex) == null) {
				result = Outcome.NO_PARENT;
				continue;
			}
			if (attachedChild(conn, parentIndex, partition) != null) {
				continue; // 已挂接（含新分区由 PG 自动克隆）
			}
			if (onlyIfEmpty) {
				if (empty == null) {
					empty = isEmpty(conn, partition);
				}
				if (!empty) {
					result = Outcome.SKIPPED_NOT_EMPTY;
					continue;
				}
			}
			String child = childIndexName(partition, parentIndex);
			try (Statement st = conn.createStatement()) {
				if (Boolean.FALSE.equals(indexValid(conn, child))) {
					log.warn("分区索引 {} 为 invalid（上次 CONCURRENTLY 中断），重建", child);
					st.execute("DROP INDEX CONCURRENTLY IF EXISTS \"" + child + "\"");
				}
				if (indexValid(conn, child) == null) {
					long t0 = System.currentTimeMillis();
					st.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS \"" + child + "\" ON \"" + partition
						+ "\" (" + spec.columns() + ")");
					log.info("分区索引已建：{}（{} ms）", child, System.currentTimeMillis() - t0);
					if (result == Outcome.ATTACHED) {
						result = Outcome.BUILT;
					}
				}
				// ATTACH 前必须确认子索引 valid：invalid 索引挂上去会让父索引永远停在 invalid
				if (!Boolean.TRUE.equals(indexValid(conn, child))) {
					throw new SQLException("分区索引 " + child + " 建完仍为 invalid，未挂接");
				}
				st.execute("ALTER INDEX " + parentIndex + " ATTACH PARTITION \"" + child + "\"");
			}
		}
		return result;
	}

	/** 父索引与其全部子索引均 indisvalid，且每个分区都有挂接子索引 */
	public static boolean allValid(Connection conn, List<IndexSpec> specs) throws SQLException {
		for (IndexSpec spec : specs) {
			if (!Boolean.TRUE.equals(indexValid(conn, spec.parentIndex()))) {
				return false;
			}
		}
		for (String partition : partitions(conn)) {
			if (!partitionReady(conn, partition, specs)) {
				return false;
			}
		}
		return true;
	}

	/** 已挂接到 parentIndex 的、建在 partition 上的子索引名；无则 null */
	private static String attachedChild(Connection conn, String parentIndex, String partition) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(
			"SELECT ci.relname FROM pg_inherits i JOIN pg_index x ON x.indexrelid = i.inhrelid"
				+ " JOIN pg_class ci ON ci.oid = i.inhrelid JOIN pg_class ct ON ct.oid = x.indrelid"
				+ " JOIN pg_class cp ON cp.oid = i.inhparent WHERE cp.relname = ? AND ct.relname = ?")) {
			ps.setString(1, parentIndex);
			ps.setString(2, partition);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	/** 索引 valid 状态；不存在返回 null */
	public static Boolean indexValid(Connection conn, String index) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(
			"SELECT x.indisvalid FROM pg_index x JOIN pg_class c ON c.oid = x.indexrelid WHERE c.relname = ?")) {
			ps.setString(1, index);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getBoolean(1) : null;
			}
		}
	}

	private static void checkIdent(String ident) throws SQLException {
		if (!SAFE_IDENT.matcher(ident).matches()) {
			throw new SQLException("非法标识符：" + ident);
		}
	}
}
