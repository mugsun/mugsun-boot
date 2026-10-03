package com.mugsun.boot.gis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.gis.entity.GisLayer;
import com.mugsun.core.tool.exception.ServiceException;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 高频空间查询：视野范围（bbox）、半径内、与某几何相交、包含、最近邻、缓冲。
 *
 * <p>有 PostGIS 时全部下沉数据库，靠 {@code idx_gis_feature_geom}（GiST）过滤，只把命中要素回给前端；
 * 没有时回落读整层 {@code data_json} 在 Java 侧算——口径一致，但要素多了就慢，响应里的
 * {@code engine} 字段会如实标明走了哪条路。
 *
 * <p>米制口径：PostGIS 侧用 {@code geography} 走真实大地距离；Java 侧用以查询点纬度为基准的
 * 局部等距近似（经度按 cos(lat) 收缩），小范围内误差可忽略，跨洲距离不要指望这条回落路径。
 */
@Service
public class GisSpatialQueryService {

	/** 赤道处每度纬度约 110574 米。预过滤框用这个下限，避免把圆内的点挡在框外。精确距离仍由 geography 判断。 */
	private static final double M_PER_DEG = 110_574d;

	private final GisSpatialSupport support;
	private final GisFeatureStore featureStore;
	private final GisGeometryCodec codec;
	private final ObjectMapper objectMapper;

	public GisSpatialQueryService(GisSpatialSupport support, GisFeatureStore featureStore,
								  GisGeometryCodec codec, ObjectMapper objectMapper) {
		this.support = support;
		this.featureStore = featureStore;
		this.codec = codec;
		this.objectMapper = objectMapper;
	}

	// ==================== 对外查询 ====================

	public Map<String, Object> bbox(GisLayer layer, double minLon, double minLat,
									double maxLon, double maxLat, int limit, boolean forceJava) {
		requireIndexable(layer);
		if (!valid(minLon, minLat) || !valid(maxLon, maxLat) || minLon >= maxLon || minLat >= maxLat) {
			throw new ServiceException(GisConstants.MSG_SPATIAL_BBOX);
		}
		int cap = clampLimit(limit);
		if (ready(layer, forceJava)) {
			return sql(layer,
				" AND geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)"
					+ " AND ST_Intersects(geom, ST_MakeEnvelope(?, ?, ?, ?, 4326))",
				new Object[] { minLon, minLat, maxLon, maxLat, minLon, minLat, maxLon, maxLat },
				null, cap);
		}
		Geometry box = codec.factory().toGeometry(
			new org.locationtech.jts.geom.Envelope(minLon, maxLon, minLat, maxLat));
		return java(layer, cap, (geom, feat) -> geom.intersects(box) ? 0d : null);
	}

	public Map<String, Object> radius(GisLayer layer, double lon, double lat, double meters,
									  int limit, boolean forceJava) {
		requireIndexable(layer);
		if (!valid(lon, lat)) {
			throw new ServiceException(GisConstants.MSG_SPATIAL_POINT);
		}
		if (meters <= 0 || meters > GisConstants.RADIUS_MAX_M) {
			throw new ServiceException(GisConstants.MSG_SPATIAL_RADIUS);
		}
		int cap = clampLimit(limit);
		if (ready(layer, forceJava)) {
			// 先用包围盒收窄（走 GiST 索引），再用 geography 精确判距；只有后者是准的，前者只为提速
			double dy = meters / M_PER_DEG;
			double dx = dy / Math.max(0.01d, Math.cos(Math.toRadians(lat)));
			return sql(layer,
				" AND geom && ST_Expand(ST_SetSRID(ST_MakePoint(?, ?), 4326), ?, ?)"
					+ " AND ST_DWithin(geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)",
				new Object[] { lon, lat, dx, dy, lon, lat, meters },
				"ST_Distance(geom::geography, ST_SetSRID(ST_MakePoint(" + lon + ", " + lat + "), 4326)::geography)",
				cap);
		}
		Metric metric = new Metric(lat);
		Coordinate center = metric.project(lon, lat);
		return java(layer, cap, (geom, feat) -> {
			double d = metric.distance(geom, center);
			return d <= meters ? d : null;
		});
	}

	public Map<String, Object> intersects(GisLayer layer, Object geometry, int limit, boolean forceJava) {
		requireIndexable(layer);
		Map<?, ?> geomMap = geometryOf(geometry);
		int cap = clampLimit(limit);
		if (ready(layer, forceJava)) {
			String json = writeJson(geomMap);
			return sql(layer,
				" AND geom && ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)"
					+ " AND ST_Intersects(geom, ST_SetSRID(ST_GeomFromGeoJSON(?), 4326))",
				new Object[] { json, json }, null, cap);
		}
		Geometry other = codec.fromGeometry(geomMap);
		if (other == null || other.isEmpty()) {
			throw new ServiceException(GisConstants.MSG_SPATIAL_GEOM);
		}
		return java(layer, cap, (geom, feat) -> geom.intersects(other) ? 0d : null);
	}

	public Map<String, Object> nearest(GisLayer layer, double lon, double lat, int limit, boolean forceJava) {
		requireIndexable(layer);
		if (!valid(lon, lat)) {
			throw new ServiceException(GisConstants.MSG_SPATIAL_POINT);
		}
		int cap = clampLimit(limit);
		if (ready(layer, forceJava)) {
			// <-> 是 GiST 的 KNN 距离算子，索引直接按距离出序，不需要先全表算距离
			String point = "ST_SetSRID(ST_MakePoint(" + lon + ", " + lat + "), 4326)";
			return sql(layer, "", new Object[0],
				"ST_Distance(geom::geography, " + point + "::geography)",
				cap, " ORDER BY geom <-> " + point);
		}
		Metric metric = new Metric(lat);
		Coordinate center = metric.project(lon, lat);
		Map<String, Object> out = java(layer, Integer.MAX_VALUE, (geom, feat) -> metric.distance(geom, center));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> feats = (List<Map<String, Object>>) out.get("features");
		feats.sort(Comparator.comparingDouble(f -> distanceOf(f)));
		if (feats.size() > cap) {
			out.put("features", new ArrayList<>(feats.subList(0, cap)));
		}
		out.put("count", ((List<?>) out.get("features")).size());
		return out;
	}

	/**
	 * 包含给定几何的要素（点落在多边形内、子区划落在上级区划内等）。
	 * 语义是逐要素 {@code ST_Contains(geom, probe)}，不是整层并集再判一次。
	 */
	public Map<String, Object> contains(GisLayer layer, Object geometry, int limit, boolean forceJava) {
		requireIndexable(layer);
		Map<?, ?> geomMap = geometryOf(geometry);
		int cap = clampLimit(limit);
		if (ready(layer, forceJava)) {
			String json = writeJson(geomMap);
			return sql(layer,
				" AND geom && ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)"
					+ " AND ST_Contains(geom, ST_SetSRID(ST_GeomFromGeoJSON(?), 4326))",
				new Object[] { json, json }, null, cap);
		}
		Geometry other = codec.fromGeometry(geomMap);
		if (other == null || other.isEmpty()) {
			throw new ServiceException(GisConstants.MSG_SPATIAL_GEOM);
		}
		return java(layer, cap, (geom, feat) -> geom.contains(other) ? 0d : null);
	}

	/**
	 * 对图层每个要素做米制缓冲，回缓冲后的几何（不是「缓冲区内的原要素」——那是 {@link #radius}）。
	 * 默认带 limit，避免万级图层一次吐出整层多边形把前端和带宽打爆。
	 */
	public Map<String, Object> buffer(GisLayer layer, double meters, int limit, boolean forceJava) {
		requireIndexable(layer);
		if (meters <= 0 || meters > GisConstants.BUFFER_MAX_M) {
			throw new ServiceException(GisConstants.MSG_ANALYZE_DISTANCE);
		}
		int cap = clampLimit(limit);
		if (ready(layer, forceJava)) {
			String select = "SELECT ST_AsGeoJSON(ST_Buffer(geom::geography, ?)::geometry) AS geom, props_json"
				+ " FROM gis_feature WHERE layer_id = ? AND is_deleted = 0 LIMIT ?";
			List<Map<String, Object>> feats = new ArrayList<>();
			support.jdbc().query(select, rs -> {
				Map<String, Object> props = readProps(rs.getString("props_json"));
				props.put("bufferMeters", meters);
				Map<String, Object> feat = new LinkedHashMap<>();
				feat.put("type", "Feature");
				feat.put("geometry", readJson(rs.getString("geom")));
				feat.put("properties", props);
				feats.add(feat);
			}, meters, layer.getId(), cap);
			return collection(feats, GisConstants.ENGINE_POSTGIS, cap);
		}
		List<Map<String, Object>> feats = new ArrayList<>();
		for (Map<?, ?> feat : features(layer)) {
			Object geomRaw = feat.get("geometry");
			if (!(geomRaw instanceof Map<?, ?> geomMap)) {
				continue;
			}
			Geometry geom;
			try {
				geom = codec.fromGeometry(geomMap);
			} catch (Exception e) {
				continue;
			}
			if (geom == null || geom.isEmpty()) {
				continue;
			}
			Geometry buf = org.locationtech.jts.operation.buffer.BufferOp.bufferOp(
				codec.toMercator(geom), meters);
			if (buf == null || buf.isEmpty()) {
				continue;
			}
			Map<String, Object> props = new LinkedHashMap<>();
			if (feat.get("properties") instanceof Map<?, ?> p) {
				p.forEach((k, v) -> props.put(String.valueOf(k), v));
			}
			props.put("bufferMeters", meters);
			feats.add(codec.toFeature(codec.toWgs84(buf), props,
				feat.get("id") == null ? null : String.valueOf(feat.get("id"))));
			if (feats.size() >= cap) {
				break;
			}
		}
		return collection(feats, GisConstants.ENGINE_JAVA, cap);
	}

	// ==================== 下沉数据库 ====================

	private Map<String, Object> sql(GisLayer layer, String where, Object[] args, String distanceExpr, int limit) {
		return sql(layer, where, args, distanceExpr, limit, "");
	}

	private Map<String, Object> sql(GisLayer layer, String where, Object[] args,
									String distanceExpr, int limit, String orderBy) {
		String select = "SELECT ST_AsGeoJSON(geom) AS geom, props_json"
			+ (distanceExpr == null ? "" : ", " + distanceExpr + " AS meters")
			+ " FROM gis_feature WHERE layer_id = ? AND is_deleted = 0" + where + orderBy + " LIMIT ?";
		Object[] full = new Object[args.length + 2];
		full[0] = layer.getId();
		System.arraycopy(args, 0, full, 1, args.length);
		full[full.length - 1] = limit;

		List<Map<String, Object>> feats = new ArrayList<>();
		support.jdbc().query(select, rs -> {
			Map<String, Object> props = readProps(rs.getString("props_json"));
			if (distanceExpr != null) {
				props.put("meters", round(rs.getDouble("meters")));
			}
			Map<String, Object> feat = new LinkedHashMap<>();
			feat.put("type", "Feature");
			feat.put("geometry", readJson(rs.getString("geom")));
			feat.put("properties", props);
			feats.add(feat);
		}, full);
		return collection(feats, GisConstants.ENGINE_POSTGIS, limit);
	}

	// ==================== 回落 Java 侧 ====================

	/** 判定器返回 null 表示不命中，返回距离（米）表示命中；bbox / 相交这类没有距离概念的回 0 */
	private interface Hit {
		Double test(Geometry geom, Map<?, ?> feature);
	}

	private Map<String, Object> java(GisLayer layer, int limit, Hit hit) {
		List<Map<String, Object>> feats = new ArrayList<>();
		for (Map<?, ?> feat : features(layer)) {
			Object geomRaw = feat.get("geometry");
			if (!(geomRaw instanceof Map<?, ?> geomMap)) {
				continue;
			}
			Geometry geom;
			try {
				geom = codec.fromGeometry(geomMap);
			} catch (Exception e) {
				continue;
			}
			if (geom == null || geom.isEmpty()) {
				continue;
			}
			Double meters = hit.test(geom, feat);
			if (meters == null) {
				continue;
			}
			Map<String, Object> props = new LinkedHashMap<>();
			if (feat.get("properties") instanceof Map<?, ?> p) {
				p.forEach((k, v) -> props.put(String.valueOf(k), v));
			}
			props.put("meters", round(meters));
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("type", "Feature");
			out.put("geometry", geomMap);
			out.put("properties", props);
			feats.add(out);
			if (feats.size() >= limit) {
				break;
			}
		}
		return collection(feats, GisConstants.ENGINE_JAVA, limit);
	}

	private List<Map<?, ?>> features(GisLayer layer) {
		List<Map<?, ?>> out = new ArrayList<>();
		String json = layer.getDataJson();
		if (json == null || json.isBlank()) {
			return out;
		}
		try {
			Map<?, ?> collection = objectMapper.readValue(json, Map.class);
			if (collection.get("features") instanceof List<?> list) {
				for (Object item : list) {
					if (item instanceof Map<?, ?> feat) {
						out.add(feat);
					}
				}
			}
		} catch (Exception e) {
			throw new ServiceException(GisConstants.MSG_LAYER_INVALID);
		}
		return out;
	}

	/**
	 * 半正矢（haversine）球面距离。
	 *
	 * <p>早先用的是「经度按 cos(lat) 收缩」的局部等距近似，2 公里半径下就会把边界要素判反
	 * （与 PostGIS 的 geography 结果差 1 个），既然两条路要求口径一致，这里按球面距离算。
	 * 与 PostGIS 的椭球距离仍有约 0.3% 的固有差异，来自球体假设而非算法。
	 */
	private static final class Metric {

		private static final double EARTH_R = 6_371_008.8d;

		Metric(double baseLat) {
			// 保留构造参数以贴合调用方语义（以查询点为基准），球面公式本身不需要基准纬度
		}

		Coordinate project(double lon, double lat) {
			return new Coordinate(lon, lat);
		}

		double distance(Geometry geom, Coordinate center) {
			double best = Double.MAX_VALUE;
			for (Coordinate c : geom.getCoordinates()) {
				best = Math.min(best, haversine(center.y, center.x, c.y, c.x));
			}
			return best;
		}

		private static double haversine(double lat1, double lon1, double lat2, double lon2) {
			double dLat = Math.toRadians(lat2 - lat1);
			double dLon = Math.toRadians(lon2 - lon1);
			double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
				+ Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
				* Math.sin(dLon / 2) * Math.sin(dLon / 2);
			return 2 * EARTH_R * Math.asin(Math.min(1d, Math.sqrt(a)));
		}
	}

	// ==================== 杂项 ====================

	/** forceJava 只给诊断与压测用：PostGIS 在线时也能走一遍回落路径，方便对比耗时、验证两条路口径一致 */
	private boolean ready(GisLayer layer, boolean forceJava) {
		if (forceJava || !support.available()) {
			return false;
		}
		int rows = featureStore.count(layer.getId());
		if (rows > 0) {
			return true;
		}
		// 存量图层（PostGIS 之前入库的）第一次被查到时按需回填，省一次全库迁移任务
		if (rows == 0 && layer.getFeatureCount() != null && layer.getFeatureCount() > 0) {
			return featureStore.backfillIfEmpty(
				layer.getId(), layer.getTenantId(), layer.getKind(), layer.getDataJson()) > 0;
		}
		return false;
	}

	private static void requireIndexable(GisLayer layer) {
		if (!GisFeatureStore.indexable(layer.getKind())) {
			throw new ServiceException(GisConstants.MSG_SPATIAL_KIND);
		}
	}

	private static Map<String, Object> collection(List<Map<String, Object>> feats, String engine, int limit) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("type", "FeatureCollection");
		out.put("features", feats);
		out.put("count", feats.size());
		out.put("engine", engine);
		out.put("truncated", limit != Integer.MAX_VALUE && feats.size() >= limit);
		return out;
	}

	private Map<?, ?> geometryOf(Object raw) {
		Object src = raw;
		if (src instanceof Map<?, ?> map && map.get("geometry") instanceof Map<?, ?> inner) {
			src = inner;
		}
		if (src instanceof Map<?, ?> map && map.get("type") != null && map.get("coordinates") != null) {
			return map;
		}
		throw new ServiceException(GisConstants.MSG_SPATIAL_GEOM);
	}

	private String writeJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (Exception e) {
			throw new ServiceException(GisConstants.MSG_SPATIAL_GEOM);
		}
	}

	private Object readJson(String json) {
		try {
			return objectMapper.readValue(json, Map.class);
		} catch (Exception e) {
			return null;
		}
	}

	private Map<String, Object> readProps(String json) {
		Map<String, Object> props = new LinkedHashMap<>();
		if (json == null || json.isBlank()) {
			return props;
		}
		try {
			Map<?, ?> parsed = objectMapper.readValue(json, Map.class);
			parsed.forEach((k, v) -> props.put(String.valueOf(k), v));
		} catch (Exception ignored) {
			// 属性坏了不影响几何返回
		}
		return props;
	}

	private static double distanceOf(Map<String, Object> feature) {
		if (feature.get("properties") instanceof Map<?, ?> props
			&& props.get("meters") instanceof Number n) {
			return n.doubleValue();
		}
		return Double.MAX_VALUE;
	}

	private static boolean valid(double lon, double lat) {
		return lon >= -180 && lon <= 180 && lat >= -90 && lat <= 90;
	}

	private static int clampLimit(int limit) {
		if (limit <= 0) {
			return GisConstants.SPATIAL_LIMIT_DEFAULT;
		}
		return Math.min(limit, GisConstants.SPATIAL_LIMIT_MAX);
	}

	private static double round(double meters) {
		return Math.round(meters * 100d) / 100d;
	}
}
