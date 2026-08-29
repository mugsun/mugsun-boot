package com.mugsun.boot.gis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
		if (layerId == null || !support.available() || !indexable(kind) || dataJson == null) {
			return -1;
		}
		try {
			List<Object[]> rows = rows(layerId, tenantId, dataJson);
			support.jdbc().update("DELETE FROM gis_feature WHERE layer_id = ?", layerId);
			int ok = 0;
			for (int from = 0; from < rows.size(); from += BATCH) {
				List<Object[]> chunk = rows.subList(from, Math.min(rows.size(), from + BATCH));
				ok += insertChunk(chunk);
			}
			if (ok < rows.size()) {
				log.warn("图层 {} 要素行写入 {}/{}，跳过的是几何非法的要素", layerId, ok, rows.size());
			}
			return ok;
		} catch (Exception e) {
			log.warn("图层 {} 要素行同步失败，空间查询将回落 Java 侧：{}", layerId, e.getMessage());
			return -1;
		}
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

	/** 只有矢量与热力图层有要素；栅格与三维切片存的是服务地址，没有几何可索引 */
	public static boolean indexable(String kind) {
		return GisConstants.KIND_VECTOR.equals(kind) || GisConstants.KIND_HEATMAP.equals(kind);
	}

	/**
	 * 整批插入；某批里只要有一个几何被 PostGIS 判非法，整批会一起回滚，
	 * 这时降级为逐行插入把好数据救回来，坏要素单独跳过。
	 */
	private int insertChunk(List<Object[]> chunk) {
		try {
			support.jdbc().batchUpdate(INSERT, chunk);
			return chunk.size();
		} catch (Exception batchFailed) {
			int ok = 0;
			for (Object[] row : chunk) {
				try {
					support.jdbc().update(INSERT, row);
					ok++;
				} catch (Exception ignored) {
					// 单个要素几何非法，跳过即可，不影响其余要素
				}
			}
			return ok;
		}
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
