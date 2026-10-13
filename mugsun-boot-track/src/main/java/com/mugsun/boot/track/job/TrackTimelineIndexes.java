package com.mugsun.boot.track.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 时间线 (ts, id) 分区索引维护（T3 父表 ON ONLY 索引的分区侧）：逐分区
 * {@code CREATE INDEX CONCURRENTLY} → {@code ALTER INDEX parent ATTACH PARTITION child}，不在父表上一次性建索引
 * （父表 CREATE INDEX 不支持 CONCURRENTLY，会对全部分区同步建并阻塞写入）。
 * <p>调用方须给 <b>autocommit=true</b> 的连接（CONCURRENTLY 不能在事务块内）。幂等：已挂接的分区跳过；
 * 上次 CONCURRENTLY 中断留下的 invalid 子索引先 DROP 再重建。T4 迁移与 {@link TrackPartitionJob} 共用。
 */
public final class TrackTimelineIndexes {

	private static final Logger log = LoggerFactory.getLogger(TrackTimelineIndexes.class);

	/** 父表 */
	public static final String PARENT_TABLE = "track_event";
	/** 父索引名 → 列（与 T3 一致） */
	public static final Map<String, String> PARENT_INDEXES = new LinkedHashMap<>();
	/** 分区名白名单（标识符拼 SQL 前校验） */
	private static final Pattern SAFE_IDENT = Pattern.compile("^[a-z0-9_]{1,63}$");

	static {
		PARENT_INDEXES.put("idx_event_user_ts_timeline", "app_key, user_id, ts, id");
		PARENT_INDEXES.put("idx_event_distinct_ts_timeline", "app_key, distinct_id, ts, id");
	}

	private TrackTimelineIndexes() {
	}

	/** 子索引名：{分区}_{父索引去 idx_event_ 前缀}，如 track_event_2026_10_user_ts_timeline（≤63） */
	static String childIndexName(String partition, String parentIndex) {
		return partition + "_" + parentIndex.substring("idx_event_".length());
	}

	/** 父表直接分区（叶子，含 default） */
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

	/** 全部分区补齐挂接；pauseMs 为两次建索引之间的停顿（限速，降 IO 峰值）。返回新建子索引数 */
	public static int ensureAll(Connection conn, long pauseMs) throws SQLException {
		int built = 0;
		for (String partition : partitions(conn)) {
			int n = ensurePartition(conn, partition);
			built += n;
			if (n > 0 && pauseMs > 0) {
				try {
					Thread.sleep(pauseMs);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new SQLException("分区索引构建被中断", e);
				}
			}
		}
		return built;
	}

	/** 单分区补齐：每条父索引若未挂接本分区的子索引，则 CONCURRENTLY 建出后 ATTACH。返回新建数 */
	public static int ensurePartition(Connection conn, String partition) throws SQLException {
		if (!SAFE_IDENT.matcher(partition).matches()) {
			throw new SQLException("非法分区名：" + partition);
		}
		int built = 0;
		for (Map.Entry<String, String> idx : PARENT_INDEXES.entrySet()) {
			String parentIndex = idx.getKey();
			if (!exists(conn, "SELECT 1 FROM pg_class WHERE relname = ? AND relkind = 'I'", parentIndex)) {
				continue; // T3 未执行（不应发生）：不在此擅建父索引
			}
			if (attachedChild(conn, parentIndex, partition) != null) {
				continue; // 已挂接（含新分区由 PG 自动克隆的情况）
			}
			String child = childIndexName(partition, parentIndex);
			Boolean valid = childValid(conn, child);
			try (Statement st = conn.createStatement()) {
				if (Boolean.FALSE.equals(valid)) {
					log.warn("分区索引 {} 为 invalid（上次 CONCURRENTLY 中断），重建", child);
					st.execute("DROP INDEX CONCURRENTLY IF EXISTS \"" + child + "\"");
					valid = null;
				}
				if (valid == null) {
					long t0 = System.currentTimeMillis();
					st.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS \"" + child + "\" ON \"" + partition
						+ "\" (" + idx.getValue() + ")");
					built++;
					log.info("分区索引已建：{}（{} ms）", child, System.currentTimeMillis() - t0);
				}
				st.execute("ALTER INDEX " + parentIndex + " ATTACH PARTITION \"" + child + "\"");
			}
		}
		return built;
	}

	/** 父索引与其全部子索引均 indisvalid，且每个分区都有挂接子索引 */
	public static boolean allValid(Connection conn) throws SQLException {
		List<String> parts = partitions(conn);
		for (String parentIndex : PARENT_INDEXES.keySet()) {
			if (!exists(conn, "SELECT 1 FROM pg_index WHERE indexrelid = ?::regclass AND indisvalid", parentIndex)) {
				return false;
			}
			for (String partition : parts) {
				String child = attachedChild(conn, parentIndex, partition);
				if (child == null || !Boolean.TRUE.equals(childValid(conn, child))) {
					return false;
				}
			}
		}
		return true;
	}

	/** 已挂接到 parentIndex 的、建在 partition 上的子索引名；无则 null */
	private static String attachedChild(Connection conn, String parentIndex, String partition) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(
			"SELECT ci.relname FROM pg_inherits i JOIN pg_index x ON x.indexrelid = i.inhrelid"
				+ " JOIN pg_class ci ON ci.oid = i.inhrelid JOIN pg_class ct ON ct.oid = x.indrelid"
				+ " WHERE i.inhparent = ?::regclass AND ct.relname = ?")) {
			ps.setString(1, parentIndex);
			ps.setString(2, partition);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	/** 索引 valid 状态；不存在返回 null */
	private static Boolean childValid(Connection conn, String index) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(
			"SELECT x.indisvalid FROM pg_index x JOIN pg_class c ON c.oid = x.indexrelid WHERE c.relname = ?")) {
			ps.setString(1, index);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getBoolean(1) : null;
			}
		}
	}

	private static boolean exists(Connection conn, String sql, String arg) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(sql)) {
			ps.setString(1, arg);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		}
	}
}
