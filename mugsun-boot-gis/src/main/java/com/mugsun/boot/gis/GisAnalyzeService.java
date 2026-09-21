package com.mugsun.boot.gis;

import com.mugsun.boot.gis.entity.GisLayer;
import com.mugsun.boot.gis.mapper.GisLayerMapper;
import com.mugsun.core.tool.exception.ServiceException;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 空间分析内核：入站与图层 ingest 同一套规范化，运算走 JTS（米制 3857，出入 WGS84）。
 * 其它业务模块只要 POST 坐标或 layerId，不必感知地图页。
 *
 * <p>当请求带 {@code layerId} 且库里有 PostGIS 时，缓冲 / 包含会转交
 * {@link GisSpatialQueryService} 下沉数据库（响应带 {@code engine}），避免把整层
 * {@code data_json} 拉进 JVM 再算一遍；没有扩展或强制 {@code engine=java} 时仍走 JTS。
 */
@Service
public class GisAnalyzeService {

	private final GisFormatService formatService;
	private final GisGeometryCodec codec;
	private final GisLayerMapper layerMapper;
	private final GisSpatialQueryService spatialQuery;

	public GisAnalyzeService(GisFormatService formatService, GisGeometryCodec codec,
							 GisLayerMapper layerMapper, GisSpatialQueryService spatialQuery) {
		this.formatService = formatService;
		this.codec = codec;
		this.layerMapper = layerMapper;
		this.spatialQuery = spatialQuery;
	}

	public Map<String, Object> analyze(Map<String, Object> body) {
		if (body == null) {
			throw new ServiceException(GisConstants.MSG_ANALYZE_EMPTY);
		}
		String op = stringOf(body.get("op"));
		if (op == null || !GisConstants.ANALYZE_OPS.contains(op)) {
			throw new ServiceException(GisConstants.MSG_ANALYZE_OP);
		}
		double distance = numberOf(body.get("distance"), GisConstants.BUFFER_DEFAULT_M);
		int limit = body.get("limit") instanceof Number n ? n.intValue() : 0;
		boolean forceJava = GisConstants.ENGINE_JAVA.equalsIgnoreCase(stringOf(body.get("engine")));
		Map<String, Object> offloaded = tryOffload(op, body, distance, limit, forceJava);
		if (offloaded != null) {
			return offloaded;
		}
		Map<String, Object> source = resolve(body.get("payload"), body.get("data"), body.get("layerId"));
		List<Geometry> geoms = codec.fromCollection(source);
		if (geoms.isEmpty()) {
			throw new ServiceException(GisConstants.MSG_ANALYZE_EMPTY);
		}
		boolean binary = GisConstants.OP_DISTANCE.equals(op)
			|| GisConstants.OP_INTERSECTS.equals(op)
			|| GisConstants.OP_CONTAINS.equals(op)
			|| GisConstants.OP_DIFFERENCE.equals(op);
		List<Geometry> others = List.of();
		if (binary) {
			Map<String, Object> other = resolve(body.get("other"), null, body.get("otherLayerId"));
			others = codec.fromCollection(other);
			if (others.isEmpty()) {
				throw new ServiceException(GisConstants.MSG_ANALYZE_OTHER);
			}
		}
		double tolerance = numberOf(body.get("tolerance"), GisConstants.SIMPLIFY_DEFAULT);
		return switch (op) {
			case GisConstants.OP_BUFFER -> buffer(source, geoms, distance, limit);
			case GisConstants.OP_CENTROID -> perFeature(source, geoms, GisConstants.OP_CENTROID,
				g -> codec.toWgs84(codec.toMercator(g).getCentroid()));
			case GisConstants.OP_BBOX -> envelope(source, geoms);
			case GisConstants.OP_AREA -> metricsOnly(source, GisConstants.OP_AREA, measure(geoms));
			case GisConstants.OP_LENGTH -> metricsOnly(source, GisConstants.OP_LENGTH, measure(geoms));
			case GisConstants.OP_DISTANCE -> distanceOf(source, geoms, others);
			case GisConstants.OP_INTERSECTS -> relate(source, geoms, others, GisConstants.OP_INTERSECTS, true);
			case GisConstants.OP_CONTAINS -> relate(source, geoms, others, GisConstants.OP_CONTAINS, false);
			case GisConstants.OP_UNION -> unary(source, geoms, GisConstants.OP_UNION, g -> g);
			case GisConstants.OP_DIFFERENCE -> difference(source, geoms, others);
			case GisConstants.OP_SIMPLIFY -> perFeature(source, geoms, GisConstants.OP_SIMPLIFY,
				g -> DouglasPeuckerSimplifier.simplify(g, Math.max(0d, tolerance)));
			case GisConstants.OP_CONVEX_HULL -> unary(source, geoms, GisConstants.OP_CONVEX_HULL,
				Geometry::convexHull);
			default -> throw new ServiceException(GisConstants.MSG_ANALYZE_OP);
		};
	}

	/**
	 * 图层级缓冲 / 包含优先下沉：有 PostGIS 时不把整层 JSON 拉进内存。
	 * 返回 null 表示走不了下沉（没 layerId、不是这两个算子、或图层不适合索引）。
	 */
	private Map<String, Object> tryOffload(String op, Map<String, Object> body,
										   double distance, int limit, boolean forceJava) {
		if (!(GisConstants.OP_BUFFER.equals(op) || GisConstants.OP_CONTAINS.equals(op))) {
			return null;
		}
		Long layerId = parseId(body.get("layerId"));
		if (layerId == null) {
			return null;
		}
		// 仍有 payload/data 时按调用方意图走内存几何，不抢下沉
		if (body.get("payload") != null || body.get("data") != null) {
			return null;
		}
		GisLayer layer = layerMapper.selectOneById(layerId);
		if (layer == null) {
			throw new ServiceException(GisConstants.MSG_LAYER_MISSING);
		}
		if (!GisFeatureStore.indexable(layer.getKind())) {
			return null;
		}
		if (GisConstants.OP_BUFFER.equals(op)) {
			Map<String, Object> spatial = spatialQuery.buffer(layer, distance, limit, forceJava);
			return wrapSpatial(GisConstants.OP_BUFFER, spatial, distance);
		}
		Object probe = body.get("other");
		if (probe == null && body.get("otherLayerId") != null) {
			Map<String, Object> other = resolve(null, null, body.get("otherLayerId"));
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> feats = (List<Map<String, Object>>) other.get("features");
			if (feats == null || feats.isEmpty()) {
				throw new ServiceException(GisConstants.MSG_ANALYZE_OTHER);
			}
			probe = feats.get(0).get("geometry");
		}
		if (probe == null) {
			throw new ServiceException(GisConstants.MSG_ANALYZE_OTHER);
		}
		Map<String, Object> spatial = spatialQuery.contains(layer, probe, limit, forceJava);
		Map<String, Object> out = wrapSpatial(GisConstants.OP_CONTAINS, spatial, null);
		@SuppressWarnings("unchecked")
		Map<String, Object> metrics = (Map<String, Object>) out.get("metrics");
		int hit = spatial.get("count") instanceof Number n ? n.intValue() : 0;
		metrics.put(GisConstants.OP_CONTAINS, hit > 0);
		return out;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> wrapSpatial(String op, Map<String, Object> spatial, Double bufferMeters) {
		List<Map<String, Object>> feats = spatial.get("features") instanceof List<?> list
			? (List<Map<String, Object>>) list : List.of();
		Map<String, Object> metrics = measure(codec.fromCollection(wrapTemp(feats)));
		if (bufferMeters != null) {
			metrics.put("bufferMeters", bufferMeters);
		}
		Map<String, Object> out = envelopeResult(op, feats, metrics);
		out.put("engine", spatial.getOrDefault("engine", GisConstants.ENGINE_JAVA));
		out.put("truncated", Boolean.TRUE.equals(spatial.get("truncated")));
		out.put("count", spatial.getOrDefault("count", feats.size()));
		return out;
	}

	private Map<String, Object> buffer(Map<String, Object> source, List<Geometry> geoms,
									   double meters, int limit) {
		if (meters <= 0 || meters > GisConstants.BUFFER_MAX_M) {
			throw new ServiceException(GisConstants.MSG_ANALYZE_DISTANCE);
		}
		int cap = limit <= 0 ? GisConstants.SPATIAL_LIMIT_DEFAULT
			: Math.min(limit, GisConstants.SPATIAL_LIMIT_MAX);
		List<Map<String, Object>> feats = new ArrayList<>();
		boolean truncated = false;
		for (int i = 0; i < geoms.size(); i++) {
			if (feats.size() >= cap) {
				truncated = true;
				break;
			}
			Geometry merc = codec.toMercator(geoms.get(i));
			Geometry buf = BufferOp.bufferOp(merc, meters);
			if (buf == null || buf.isEmpty()) {
				continue;
			}
			Map<String, Object> props = featureProps(source, i, GisConstants.OP_BUFFER);
			props.put("bufferMeters", meters);
			feats.add(codec.toFeature(codec.toWgs84(buf), props, idOf(source, i)));
		}
		Map<String, Object> metrics = measure(codec.fromCollection(wrapTemp(feats)));
		metrics.put("bufferMeters", meters);
		Map<String, Object> out = envelopeResult(GisConstants.OP_BUFFER, feats, metrics);
		out.put("engine", GisConstants.ENGINE_JAVA);
		out.put("truncated", truncated);
		out.put("count", feats.size());
		return out;
	}

	private Map<String, Object> envelope(Map<String, Object> source, List<Geometry> geoms) {
		Geometry unioned = UnaryUnionOp.union(geoms);
		Geometry env = unioned.getEnvelope();
		Map<String, Object> props = new LinkedHashMap<>();
		props.put("name", "外包矩形");
		props.put("kind", "polygon");
		props.put("op", GisConstants.OP_BBOX);
		Map<String, Object> metrics = measure(List.of(env));
		org.locationtech.jts.geom.Envelope box = unioned.getEnvelopeInternal();
		metrics.put("minLon", box.getMinX());
		metrics.put("minLat", box.getMinY());
		metrics.put("maxLon", box.getMaxX());
		metrics.put("maxLat", box.getMaxY());
		return envelopeResult(GisConstants.OP_BBOX, List.of(codec.toFeature(env, props, idOf(source, 0))), metrics);
	}

	private Map<String, Object> distanceOf(Map<String, Object> source, List<Geometry> left, List<Geometry> right) {
		Geometry a = codec.toMercator(UnaryUnionOp.union(left));
		Geometry b = codec.toMercator(UnaryUnionOp.union(right));
		double meters = DistanceOp.distance(a, b);
		Map<String, Object> metrics = measure(left);
		metrics.put("distanceMeters", meters);
		return envelopeResult(GisConstants.OP_DISTANCE, copyFeatures(source), metrics);
	}

	private Map<String, Object> relate(Map<String, Object> source, List<Geometry> left, List<Geometry> right,
									   String op, boolean intersects) {
		Geometry a = UnaryUnionOp.union(left);
		Geometry b = UnaryUnionOp.union(right);
		boolean hit = intersects ? a.intersects(b) : a.contains(b);
		Map<String, Object> metrics = measure(left);
		metrics.put(op, hit);
		return envelopeResult(op, copyFeatures(source), metrics);
	}

	private Map<String, Object> difference(Map<String, Object> source, List<Geometry> left, List<Geometry> right) {
		Geometry result = UnaryUnionOp.union(left).difference(UnaryUnionOp.union(right));
		Map<String, Object> props = new LinkedHashMap<>();
		props.put("name", "差集");
		props.put("kind", kindOf(result));
		props.put("op", GisConstants.OP_DIFFERENCE);
		List<Map<String, Object>> feats = result.isEmpty()
			? List.of()
			: List.of(codec.toFeature(result, props, idOf(source, 0)));
		return envelopeResult(GisConstants.OP_DIFFERENCE, feats, measure(result.isEmpty() ? List.of() : List.of(result)));
	}

	private Map<String, Object> unary(Map<String, Object> source, List<Geometry> geoms, String op,
									  java.util.function.Function<Geometry, Geometry> fn) {
		Geometry result = fn.apply(UnaryUnionOp.union(geoms));
		Map<String, Object> props = new LinkedHashMap<>();
		props.put("name", op);
		props.put("kind", kindOf(result));
		props.put("op", op);
		List<Map<String, Object>> feats = result == null || result.isEmpty()
			? List.of()
			: List.of(codec.toFeature(result, props, idOf(source, 0)));
		return envelopeResult(op, feats, measure(feats.isEmpty() ? List.of() : List.of(result)));
	}

	private Map<String, Object> perFeature(Map<String, Object> source, List<Geometry> geoms, String op,
										   java.util.function.Function<Geometry, Geometry> fn) {
		List<Map<String, Object>> feats = new ArrayList<>();
		for (int i = 0; i < geoms.size(); i++) {
			Geometry next = fn.apply(geoms.get(i));
			if (next == null || next.isEmpty()) {
				continue;
			}
			Map<String, Object> props = featureProps(source, i, op);
			feats.add(codec.toFeature(next, props, idOf(source, i)));
		}
		return envelopeResult(op, feats, measure(codec.fromCollection(wrapTemp(feats))));
	}

	private Map<String, Object> metricsOnly(Map<String, Object> source, String op, Map<String, Object> metrics) {
		return envelopeResult(op, copyFeatures(source), metrics);
	}

	private Map<String, Object> resolve(Object payload, Object data, Object layerId) {
		Object raw = payload != null ? payload : data;
		if (raw != null && !isBlank(raw)) {
			return formatService.normalizeUnknown(raw);
		}
		Long id = parseId(layerId);
		if (id == null) {
			throw new ServiceException(GisConstants.MSG_ANALYZE_EMPTY);
		}
		GisLayer row = layerMapper.selectOneById(id);
		if (row == null || row.getDataJson() == null || row.getDataJson().isBlank()) {
			throw new ServiceException(GisConstants.MSG_LAYER_MISSING);
		}
		if (GisRasterSpec.isRaster(row.getKind())) {
			throw new ServiceException(GisConstants.MSG_ANALYZE_EMPTY);
		}
		return formatService.normalizeUnknown(row.getDataJson());
	}

	private Map<String, Object> envelopeResult(String op, List<Map<String, Object>> features,
											   Map<String, Object> metrics) {
		List<Map<String, Object>> feats = features == null ? List.of() : features;
		Map<String, Object> collection;
		if (feats.isEmpty()) {
			collection = new LinkedHashMap<>();
			collection.put("mugsunGis", GisConstants.SPEC_VERSION);
			collection.put("crs", GisConstants.CRS_WGS84);
			collection.put("type", "FeatureCollection");
			collection.put("count", 0);
			collection.put("features", List.of());
		} else {
			collection = formatService.normalizeUnknown(Map.of("type", "FeatureCollection", "features", feats));
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("op", op);
		out.put("crs", GisConstants.CRS_WGS84);
		out.put("metrics", metrics);
		out.put("collection", collection);
		return out;
	}

	private Map<String, Object> measure(List<Geometry> geoms) {
		double area = 0d;
		double length = 0d;
		for (Geometry geom : geoms) {
			Geometry merc = codec.toMercator(geom);
			area += merc.getArea();
			length += merc.getLength();
		}
		Map<String, Object> metrics = new LinkedHashMap<>();
		metrics.put("count", geoms.size());
		metrics.put("areaSqMeters", area);
		metrics.put("lengthMeters", length);
		return metrics;
	}

	private Map<String, Object> wrapTemp(List<Map<String, Object>> feats) {
		Map<String, Object> tmp = new LinkedHashMap<>();
		tmp.put("type", "FeatureCollection");
		tmp.put("features", feats);
		return tmp;
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> copyFeatures(Map<String, Object> source) {
		Object raw = source.get("features");
		if (!(raw instanceof List<?> list)) {
			return List.of();
		}
		List<Map<String, Object>> out = new ArrayList<>();
		for (Object item : list) {
			if (item instanceof Map<?, ?> feat) {
				out.add(new LinkedHashMap<>((Map<String, Object>) feat));
			}
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> featureProps(Map<String, Object> source, int index, String op) {
		Map<String, Object> props = new LinkedHashMap<>();
		Object raw = source.get("features");
		if (raw instanceof List<?> list && index < list.size() && list.get(index) instanceof Map<?, ?> feat) {
			Object p = feat.get("properties");
			if (p instanceof Map<?, ?> map) {
				props.putAll((Map<String, Object>) map);
			}
		}
		props.put("op", op);
		return props;
	}

	private String idOf(Map<String, Object> source, int index) {
		Object raw = source.get("features");
		if (raw instanceof List<?> list && index < list.size() && list.get(index) instanceof Map<?, ?> feat) {
			Object id = feat.get("id");
			return id == null ? null : String.valueOf(id);
		}
		return null;
	}

	private static String kindOf(Geometry geom) {
		String t = geom.getGeometryType().toLowerCase(Locale.ROOT);
		if (t.contains("point")) {
			return "point";
		}
		if (t.contains("line")) {
			return "line";
		}
		return "polygon";
	}

	private static boolean isBlank(Object raw) {
		if (raw instanceof String s) {
			return s.isBlank();
		}
		if (raw instanceof Map<?, ?> map) {
			return map.isEmpty();
		}
		return false;
	}

	public static Long parseId(Object idVal) {
		if (idVal instanceof Number n) {
			return n.longValue();
		}
		if (idVal instanceof String s) {
			String t = s.trim();
			if (t.matches("\\d+")) {
				return Long.parseLong(t);
			}
		}
		return null;
	}

	private static double numberOf(Object v, double fallback) {
		if (v instanceof Number n) {
			return n.doubleValue();
		}
		if (v instanceof String s && !s.isBlank()) {
			try {
				return Double.parseDouble(s.trim());
			} catch (NumberFormatException ignored) {
				return fallback;
			}
		}
		return fallback;
	}

	private static String stringOf(Object v) {
		return v == null ? null : String.valueOf(v).trim();
	}
}
