package com.mugsun.boot.gis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 通用 GIS 入站规范化：任意业务只要坐标能被识别，就收成 WGS84 GeoJSON FeatureCollection。
 * 不绑定埋点/表单/标绘字段名；额外属性原样保留，方便其它模块回读。
 */
@Service
public class GisFormatService {

	private static final String[] LON_KEYS = { "lon", "lng", "longitude", "x", "$lon", "gcjLon", "geo_lon", "$geo_lon" };
	private static final String[] LAT_KEYS = { "lat", "latitude", "y", "$lat", "gcjLat", "geo_lat", "$geo_lat" };

	/**
	 * 无歧义的经纬度键。这些键一旦出现就说明作者意图是坐标，值越界属于数据错误，必须拒收；
	 * 而 {@code x} / {@code y} 在业务记录里可能是任意数值（工位号、像素坐标），
	 * 越界只当「这不是坐标」忽略，不能因此把整批拒掉。
	 */
	private static final java.util.Set<String> LON_KEYS_STRICT = java.util.Set.of(
		"lon", "lng", "longitude", "$lon", "gcjLon", "geo_lon", "$geo_lon");
	private static final java.util.Set<String> LAT_KEYS_STRICT = java.util.Set.of(
		"lat", "latitude", "$lat", "gcjLat", "geo_lat", "$geo_lat");

	private static final java.util.Set<String> GEOM_TYPES = java.util.Set.of(
		"Point", "MultiPoint", "LineString", "MultiLineString", "Polygon", "MultiPolygon");

	private final ObjectMapper objectMapper;
	private final GisTextIngest textIngest;
	private final GisTopologyGuard topologyGuard;

	public GisFormatService(ObjectMapper objectMapper, GisTextIngest textIngest,
							GisTopologyGuard topologyGuard) {
		this.objectMapper = objectMapper;
		this.textIngest = textIngest;
		this.topologyGuard = topologyGuard;
	}

	public Map<String, Object> normalize(JsonNode raw) {
		List<Map<String, Object>> features = collect(raw);
		if (features.isEmpty()) {
			throw new ServiceException(GisConstants.MSG_LAYER_EMPTY);
		}
		if (features.size() > GisConstants.FEATURE_MAX) {
			throw new ServiceException(GisConstants.MSG_LAYER_TOO_MANY);
		}
		// 拓扑校验放在计 bbox 之前：越界坐标会把 bbox 撑成全球，后续按包围盒预筛的查询全部失效
		GisTopologyGuard.Result checked = topologyGuard.inspect(features);
		features = checked.features();
		double[] bbox = bboxOf(features);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("mugsunGis", GisConstants.SPEC_VERSION);
		out.put("crs", GisConstants.CRS_WGS84);
		out.put("type", "FeatureCollection");
		out.put("count", features.size());
		if (bbox != null) {
			out.put("bbox", bbox);
		}
		if (!checked.warnings().isEmpty()) {
			out.put("warnings", checked.warnings());
		}
		out.put("features", features);
		return out;
	}

	public Map<String, Object> normalizeUnknown(Object payload) {
		if (payload instanceof String s) {
			return fromTextOrJson(s);
		}
		JsonNode node;
		try {
			node = objectMapper.valueToTree(payload);
		} catch (Exception e) {
			throw new ServiceException(GisConstants.MSG_LAYER_INVALID);
		}
		return normalize(node);
	}

	private Map<String, Object> fromTextOrJson(String raw) {
		String text = raw == null ? "" : raw.trim();
		if (text.isEmpty()) {
			throw new ServiceException(GisConstants.MSG_LAYER_EMPTY);
		}
		boolean looksJson = text.startsWith("{") || text.startsWith("[") || text.startsWith("\"");
		if (looksJson) {
			try {
				JsonNode node = objectMapper.readTree(text);
				if (node != null && node.isTextual()) {
					return fromTextOrJson(node.asText());
				}
				return normalize(node);
			} catch (ServiceException e) {
				throw e;
			} catch (Exception e) {
				Map<String, Object> fromText = wrapText(text);
				if (fromText != null) {
					return fromText;
				}
				throw new ServiceException(GisConstants.MSG_LAYER_INVALID);
			}
		}
		Map<String, Object> fromText = wrapText(text);
		if (fromText == null) {
			throw new ServiceException(GisConstants.MSG_LAYER_EMPTY);
		}
		return fromText;
	}

	private Map<String, Object> wrapText(String text) {
		List<Map<String, Object>> feats = textIngest.parse(text);
		if (feats.isEmpty()) {
			return null;
		}
		Map<String, Object> fc = new LinkedHashMap<>();
		fc.put("type", "FeatureCollection");
		fc.put("features", feats);
		return normalize(objectMapper.valueToTree(fc));
	}

	private List<Map<String, Object>> collect(JsonNode raw) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (raw == null || raw.isNull() || raw.isMissingNode()) {
			return out;
		}
		if (raw.isTextual()) {
			return collectText(raw.asText());
		}
		if (raw.isArray()) {
			for (JsonNode item : raw) {
				out.addAll(collect(item));
			}
			return out;
		}
		if (!raw.isObject()) {
			return out;
		}
		String type = raw.path("type").asText("");
		if (GEOM_TYPES.contains(type) && raw.has("coordinates")) {
			Map<String, Object> feat = wrapFeature(null, objectMapper.createObjectNode(), raw);
			if (feat != null) {
				out.add(feat);
			}
			return out;
		}
		if ("GeometryCollection".equals(type) && raw.path("geometries").isArray()) {
			for (JsonNode item : raw.path("geometries")) {
				out.addAll(collect(item));
			}
			return out;
		}
		if ("FeatureCollection".equals(type) && raw.path("features").isArray()) {
			for (JsonNode item : raw.path("features")) {
				Map<String, Object> feat = asFeature(item);
				if (feat != null) {
					out.add(feat);
				}
			}
			return out;
		}
		if ("Feature".equals(type)) {
			Map<String, Object> feat = asFeature(raw);
			if (feat != null) {
				out.add(feat);
			}
			return out;
		}
		if (raw.has("layers")) {
			for (JsonNode layer : raw.path("layers")) {
				if (layer != null && layer.path("features").isArray()) {
					for (JsonNode item : layer.path("features")) {
						Map<String, Object> feat = asFeature(item);
						if (feat != null) {
							out.add(feat);
						}
					}
				}
			}
			if (!out.isEmpty()) {
				return out;
			}
		}
		if (raw.has("collection")) {
			return collect(raw.get("collection"));
		}
		if (raw.has("data")) {
			List<Map<String, Object>> nested = collect(raw.get("data"));
			if (!nested.isEmpty()) {
				return nested;
			}
		}
		Map<String, Object> one = asFeature(raw);
		if (one != null) {
			out.add(one);
		} else {
			Map<String, Object> point = recordAsPoint(raw);
			if (point != null) {
				out.add(point);
			}
		}
		return out;
	}

	private List<Map<String, Object>> collectText(String text) {
		try {
			return collect(objectMapper.readTree(text));
		} catch (Exception e) {
			Map<String, Object> wrapped = wrapText(text);
			if (wrapped == null) {
				return List.of();
			}
			Object feats = wrapped.get("features");
			if (feats instanceof List<?> list) {
				List<Map<String, Object>> out = new ArrayList<>();
				for (Object item : list) {
					if (item instanceof Map<?, ?> map) {
						@SuppressWarnings("unchecked")
						Map<String, Object> feat = (Map<String, Object>) map;
						out.add(feat);
					}
				}
				return out;
			}
			return List.of();
		}
	}

	private Map<String, Object> asFeature(JsonNode raw) {
		if (raw == null || !raw.isObject()) {
			return null;
		}
		JsonNode geom = raw.get("geometry");
		if (geom != null && geom.isObject() && geom.has("coordinates") && geom.has("type")) {
			return wrapFeature(raw.path("id"), raw.path("properties"), geom);
		}
		return recordAsPoint(raw);
	}

	private Map<String, Object> recordAsPoint(JsonNode raw) {
		if (raw == null || !raw.isObject()) {
			return null;
		}
		double[] ll = lonLat(raw);
		if (ll == null && raw.has("properties") && raw.get("properties").isObject()) {
			ll = lonLat(raw.get("properties"));
		}
		if (ll == null && raw.has("location") && raw.get("location").isObject()) {
			ll = lonLat(raw.get("location"));
		}
		if (ll == null) {
			ll = parseLonLatText(firstText(raw, "lonlat", "lnglat", "coord", "position", "gps"));
		}
		if (ll == null) {
			return null;
		}
		Map<String, Object> geom = new LinkedHashMap<>();
		geom.put("type", "Point");
		geom.put("coordinates", List.of(ll[0], ll[1]));
		return wrapFeature(raw.path("id"), raw.has("properties") ? raw.get("properties") : raw,
			objectMapper.valueToTree(geom));
	}

	private Map<String, Object> wrapFeature(JsonNode idNode, JsonNode propsNode, JsonNode geom) {
		Map<String, Object> props = copyProps(propsNode);
		String geomType = geom.path("type").asText("Point");
		props.putIfAbsent("kind", kindOf(String.valueOf(props.get("kind")), geomType));
		if (!hasName(props)) {
			Object fallback = props.get("title");
			if (fallback == null) {
				fallback = props.get("label");
			}
			if (fallback == null) {
				fallback = props.get("address");
			}
			props.put("name", fallback == null || String.valueOf(fallback).isBlank()
				? String.valueOf(props.get("kind")) : String.valueOf(fallback));
		}
		props.putIfAbsent("visible", true);
		Map<String, Object> feat = new LinkedHashMap<>();
		feat.put("type", "Feature");
		if (idNode != null && !idNode.isMissingNode() && !idNode.isNull() && !idNode.asText("").isBlank()) {
			feat.put("id", idNode.asText());
		}
		feat.put("properties", props);
		feat.put("geometry", objectMapper.convertValue(geom, Map.class));
		return feat;
	}

	private Map<String, Object> copyProps(JsonNode propsNode) {
		Map<String, Object> props = new LinkedHashMap<>();
		if (propsNode == null || !propsNode.isObject()) {
			return props;
		}
		Iterator<String> names = propsNode.fieldNames();
		while (names.hasNext()) {
			String key = names.next();
			if (skipProp(key)) {
				continue;
			}
			JsonNode v = propsNode.get(key);
			if (v == null || v.isNull() || v.isMissingNode()) {
				continue;
			}
			if (v.isNumber()) {
				props.put(key, v.numberValue());
			} else if (v.isBoolean()) {
				props.put(key, v.asBoolean());
			} else if (v.isTextual()) {
				props.put(key, v.asText());
			} else {
				props.put(key, objectMapper.convertValue(v, Object.class));
			}
		}
		return props;
	}

	private static boolean skipProp(String key) {
		String k = key.toLowerCase(Locale.ROOT);
		return "geometry".equals(k) || "type".equals(k) || "features".equals(k) || "coordinates".equals(k)
			|| "mugsungis".equals(k);
	}

	private static boolean hasName(Map<String, Object> props) {
		Object name = props.get("name");
		return name != null && !String.valueOf(name).isBlank();
	}

	private static String kindOf(String raw, String geomType) {
		if (raw != null && !raw.isBlank() && !"null".equals(raw)) {
			return raw;
		}
		if ("Point".equals(geomType) || "MultiPoint".equals(geomType)) {
			return "point";
		}
		if ("LineString".equals(geomType) || "MultiLineString".equals(geomType)) {
			return "line";
		}
		return "polygon";
	}

	private static double[] lonLat(JsonNode obj) {
		Double lon = firstNumber(obj, LON_KEYS);
		Double lat = firstNumber(obj, LAT_KEYS);
		if (lon == null || lat == null) {
			return null;
		}
		if (lon < -180 || lon > 180 || lat < -90 || lat > 90) {
			// 明确写着 lon/lat 却越界，是坐标系搞错（典型：把墨卡托米当经纬度传），
			// 静默丢弃会让用户以为数据存进去了，直接拒收
			if (hasAny(obj, LON_KEYS_STRICT) && hasAny(obj, LAT_KEYS_STRICT)) {
				throw new ServiceException(String.format(GisConstants.MSG_TOPO_RECORD_RANGE,
					plain(lon) + ", " + plain(lat)));
			}
			return null;
		}
		return new double[] { lon, lat };
	}

	/** 报错文案里不要出现 1.2958065E7 这种科学计数法，用户对不上自己传的值 */
	private static String plain(double v) {
		return new java.math.BigDecimal(String.valueOf(v)).stripTrailingZeros().toPlainString();
	}

	private static boolean hasAny(JsonNode obj, java.util.Set<String> keys) {
		for (String key : keys) {
			JsonNode v = obj.get(key);
			if (v != null && (v.isNumber() || v.isTextual())) {
				return true;
			}
		}
		return false;
	}

	private static Double firstNumber(JsonNode obj, String[] keys) {
		for (String key : keys) {
			JsonNode v = obj.get(key);
			if (v != null && v.isNumber()) {
				return v.doubleValue();
			}
			if (v != null && v.isTextual()) {
				try {
					return Double.parseDouble(v.asText().trim());
				} catch (NumberFormatException ignored) {
					// next key
				}
			}
		}
		return null;
	}

	private static String firstText(JsonNode obj, String... keys) {
		for (String key : keys) {
			JsonNode v = obj.get(key);
			if (v != null && v.isTextual() && !v.asText().isBlank()) {
				return v.asText();
			}
		}
		return null;
	}

	private static double[] parseLonLatText(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String[] parts = raw.trim().split("[,\\s]+");
		if (parts.length < 2) {
			return null;
		}
		try {
			double a = Double.parseDouble(parts[0]);
			double b = Double.parseDouble(parts[1]);
			if (Math.abs(a) <= 180 && Math.abs(b) <= 90) {
				return new double[] { a, b };
			}
			if (Math.abs(a) <= 90 && Math.abs(b) <= 180) {
				return new double[] { b, a };
			}
		} catch (NumberFormatException ignored) {
			return null;
		}
		return null;
	}

	private static double[] bboxOf(List<Map<String, Object>> features) {
		double[] box = { 180, 90, -180, -90 };
		boolean any = false;
		for (Map<String, Object> feat : features) {
			Object geom = feat.get("geometry");
			if (geom instanceof Map<?, ?> g && walkCoords(g.get("coordinates"), box)) {
				any = true;
			}
		}
		return any ? box : null;
	}

	private static boolean walkCoords(Object coords, double[] box) {
		if (coords instanceof List<?> list) {
			if (list.size() >= 2 && list.get(0) instanceof Number && list.get(1) instanceof Number) {
				double lon = ((Number) list.get(0)).doubleValue();
				double lat = ((Number) list.get(1)).doubleValue();
				box[0] = Math.min(box[0], lon);
				box[1] = Math.min(box[1], lat);
				box[2] = Math.max(box[2], lon);
				box[3] = Math.max(box[3], lat);
				return true;
			}
			boolean any = false;
			for (Object item : list) {
				any |= walkCoords(item, box);
			}
			return any;
		}
		return false;
	}
}
