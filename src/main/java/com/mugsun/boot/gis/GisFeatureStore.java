package com.mugsun.boot.gis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 要素行写入：把图层的 GeoJSON FeatureCollection 拆成 {@code gis_feature} 一要素一行。
 *
 * <p>要素行是 {@code gis_layer.data_json} 的<b>加速副本</b>，不是唯一真相——写失败只影响空间查询
 * 是否能下沉，不影响图层本身的读写。所以这里所有异常都吞掉转日志，绝不让它冒到保存图层的请求上。
 */
@Component
public class GisFeatureStore {

	private static final Logger log = LoggerFactory.getLogger(GisFeatureStore.class);

	/** 分批插入，单批太大时 PG 的参数上限与内存都不划算 */
	private static final int BATCH = 500;

	private static final String INSERT = "INSERT INTO gis_feature"
		+ " (tenant_id, layer_id, geom, props_json, create_time, update_time, is_deleted)"
		+ " VALUES (?, ?, ST_SetSRID(ST_GeomFromGeoJSON(?), 4326), ?, now(), now(), 0)";

	private final GisSpatialSupport support;
	private final ObjectMapper objectMapper;

	public GisFeatureStore(GisSpatialSupport support, ObjectMapper objectMapper) {
		this.support = support;
		this.objectMapper = objectMapper;
	}

	/**
	 * 重建某图层的要素行（先清后插）。返回实际落库的要素数，不支持或失败返回 -1。
	 */
	public int sync(Long layerId, String tenantId, String kind, String dataJson) {
		return sync(layerId, tenantId, kind, dataJson, false);
	}

	/**
	 * 存量图层被查到时按需回填。与 {@link #sync} 的差别是拿到锁后会再查一次行数，
	 * 已经有人填好就直接用——并发首枪只让一个请求真干活，其余等锁后复用结果。
	 */
	public int backfillIfEmpty(Long layerId, String tenantId, String kind, String dataJson) {
		return sync(layerId, tenantId, kind, dataJson, true);
	}

	/**
	 * 整个「清空 + 重插」必须是一个事务里的一件事，且同一图层全集群串行，否则并发首枪会互相穿插：
	 * A 删完正插一半，B 又删（把 A 已插的删掉）再插，最终行数既不是 0 也不是要素数，
	 * 而是随机的重复行——实测 8 并发首枪把 8000 要素灌成 62235 行，空间查询从此返回重复要素。
	 *
	 * <p>串行用 PostgreSQL 咨询锁而不是 JVM 锁：多实例部署时 JVM 锁互相看不见，照样穿插。
	 * {@code pg_advisory_xact_lock} 随事务提交自动释放，不会漏锁。
	 *
	 * @param skipIfPresent true 表示拿到锁后若已有要素行就不重建（按需回填语义）
	 */
	private int sync(Long layerId, String tenantId, String kind, String dataJson, boolean skipIfPresent) {
		if (layerId == null || !support.available() || !indexable(kind) || dataJson == null) {
			return -1;
		}
		try {
			List<Object[]> rows = rows(layerId, tenantId, dataJson);
			Integer ok = support.jdbc().execute(
				(ConnectionCallback<Integer>) conn -> rebuild(conn, layerId, rows, skipIfPresent));
			if (ok != null && ok >= 0 && ok < rows.size()) {
				log.warn("图层 {} 要素行写入 {}/{}，跳过的是几何非法的要素", layerId, ok, rows.size());
			}
			return ok == null ? -1 : ok;
		} catch (Exception e) {
			log.warn("图层 {} 要素行同步失败，空间查询将回落 Java 侧：{}", layerId, e.getMessage());
			return -1;
		}
	}

	private int rebuild(Connection conn, Long layerId, List<Object[]> rows, boolean skipIfPresent)
		throws SQLException {
		boolean auto = conn.getAutoCommit();
		conn.setAutoCommit(false);
		try {
			lockLayer(conn, layerId);
			if (skipIfPresent) {
				int present = countInTx(conn, layerId);
				if (present > 0) {
					conn.commit();
					return present;
				}
			}
			try (PreparedStatement del = conn.prepareStatement("DELETE FROM gis_feature WHERE layer_id = ?")) {
				del.setLong(1, layerId);
				del.executeUpdate();
			}
			int ok = insertRows(conn, rows);
			conn.commit();
			return ok;
		} catch (SQLException e) {
			conn.rollback();
			throw e;
		} finally {
			conn.setAutoCommit(auto);
		}
	}

	private static void lockLayer(Connection conn, Long layerId) throws SQLException {
		try (PreparedStatement lock = conn.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
			lock.setLong(1, layerId);
			lock.execute();
		}
	}

	private static int countInTx(Connection conn, Long layerId) throws SQLException {
		try (PreparedStatement ps = conn.prepareStatement(
			"SELECT count(*) FROM gis_feature WHERE layer_id = ? AND is_deleted = 0")) {
			ps.setLong(1, layerId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		}
	}

	/**
	 * 分批插入。批内只要有一个几何被 PostGIS 判非法，整批会失败并把事务标记为异常，
	 * 后续语句全都执行不了——所以每批前打保存点，失败就回到保存点再逐行救数据。
	 */
	private int insertRows(Connection conn, List<Object[]> rows) throws SQLException {
		int ok = 0;
		for (int from = 0; from < rows.size(); from += BATCH) {
			List<Object[]> chunk = rows.subList(from, Math.min(rows.size(), from + BATCH));
			Savepoint sp = conn.setSavepoint();
			try (PreparedStatement ps = conn.prepareStatement(INSERT)) {
				for (Object[] row : chunk) {
					bind(ps, row);
					ps.addBatch();
				}
				ps.executeBatch();
				ok += chunk.size();
			} catch (SQLException batchFailed) {
				conn.rollback(sp);
				ok += insertOneByOne(conn, chunk);
			}
		}
		return ok;
	}

	private int insertOneByOne(Connection conn, List<Object[]> chunk) throws SQLException {
		int ok = 0;
		for (Object[] row : chunk) {
			Savepoint sp = conn.setSavepoint();
			try (PreparedStatement ps = conn.prepareStatement(INSERT)) {
				bind(ps, row);
				ps.executeUpdate();
				ok++;
			} catch (SQLException ignored) {
				// 单个要素几何非法，回到保存点跳过即可，不影响其余要素
				conn.rollback(sp);
			}
		}
		return ok;
	}

	private static void bind(PreparedStatement ps, Object[] row) throws SQLException {
		ps.setString(1, (String) row[0]);
		ps.setLong(2, (Long) row[1]);
		ps.setString(3, (String) row[2]);
		ps.setString(4, (String) row[3]);
	}

	public void dropLayer(Long layerId) {
		if (layerId == null || !support.available()) {
			return;
		}
		try {
			support.jdbc().update("DELETE FROM gis_feature WHERE layer_id = ?", layerId);
		} catch (Exception e) {
			log.warn("图层 {} 要素行清理失败：{}", layerId, e.getMessage());
		}
	}

	/** 该图层当前有多少要素行，用于判断存量图层要不要回填 */
	public int count(Long layerId) {
		if (layerId == null || !support.available()) {
			return -1;
		}
		try {
			Integer n = support.jdbc().queryForObject(
				"SELECT count(*) FROM gis_feature WHERE layer_id = ? AND is_deleted = 0", Integer.class, layerId);
			return n == null ? 0 : n;
		} catch (Exception e) {
			return -1;
		}
	}

	/**
	 * 副本对账：要素行是 data_json 的加速副本，写失败只记日志不报错，所以必须能主动查出漂移的图层
	 * （行数与 gis_layer.feature_count 不一致，或该填却一行没有）。
	 *
	 * <p>只看可索引图层；几何非法的要素本就插不进去，所以差值不一定是故障，但需要被看见。
	 * 返回按差值绝对值从大到小，最多 limit 条。
	 */
	public List<Map<String, Object>> drift(int limit) {
		if (!support.available()) {
			return List.of();
		}
		int cap = limit <= 0 ? 50 : Math.min(limit, 500);
		String sql = "SELECT l.id, l.name, l.kind, coalesce(l.feature_count, 0) AS expected,"
			+ " (SELECT count(*) FROM gis_feature f WHERE f.layer_id = l.id AND f.is_deleted = 0) AS actual"
			+ " FROM gis_layer l WHERE l.is_deleted = 0 AND l.kind IN (?, ?)"
			+ " AND coalesce(l.feature_count, 0) <> (SELECT count(*) FROM gis_feature f"
			+ " WHERE f.layer_id = l.id AND f.is_deleted = 0)"
			+ " ORDER BY abs(coalesce(l.feature_count, 0) - (SELECT count(*) FROM gis_feature f"
			+ " WHERE f.layer_id = l.id AND f.is_deleted = 0)) DESC LIMIT " + cap;
		try {
			List<Map<String, Object>> rows = support.jdbc().queryForList(
				sql, GisConstants.KIND_VECTOR, GisConstants.KIND_HEATMAP);
			List<Map<String, Object>> out = new ArrayList<>(rows.size());
			for (Map<String, Object> row : rows) {
				long expected = row.get("expected") instanceof Number n ? n.longValue() : 0L;
				long actual = row.get("actual") instanceof Number n ? n.longValue() : 0L;
				Map<String, Object> item = new java.util.LinkedHashMap<>();
				item.put("layerId", row.get("id"));
				item.put("name", row.get("name"));
				item.put("kind", row.get("kind"));
				item.put("expected", expected);
				item.put("actual", actual);
				// 一行都没有 = 存量图层还没回填；有行但数不对 = 真漂移，要重存图层重建
				item.put("reason", actual == 0 ? "missing" : "mismatch");
				out.add(item);
			}
			return out;
		} catch (Exception e) {
			log.warn("要素行对账失败：{}", e.getMessage());
			return List.of();
		}
	}

	/** 只有矢量与热力图层有要素；栅格与三维切片存的是服务地址，没有几何可索引 */
	public static boolean indexable(String kind) {
		return GisConstants.KIND_VECTOR.equals(kind) || GisConstants.KIND_HEATMAP.equals(kind);
	}

	private List<Object[]> rows(Long layerId, String tenantId, String dataJson) throws Exception {
		Map<?, ?> collection = objectMapper.readValue(dataJson, Map.class);
		Object feats = collection.get("features");
		List<Object[]> rows = new ArrayList<>();
		if (!(feats instanceof List<?> list)) {
			return rows;
		}
		for (Object item : list) {
			if (!(item instanceof Map<?, ?> feat)) {
				continue;
			}
			Object geom = feat.get("geometry");
			if (!(geom instanceof Map<?, ?> g) || g.get("type") == null || g.get("coordinates") == null) {
				continue;
			}
			String geomJson = objectMapper.writeValueAsString(g);
			Object props = feat.get("properties");
			String propsJson = props == null ? null : objectMapper.writeValueAsString(props);
			rows.add(new Object[] { tenantId, layerId, geomJson, propsJson });
		}
		return rows;
	}
}
