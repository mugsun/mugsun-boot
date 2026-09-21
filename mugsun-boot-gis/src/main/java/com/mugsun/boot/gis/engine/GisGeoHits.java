package com.mugsun.boot.gis.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 检索/逆地理对外结构：一律 WGS84。
 */
public final class GisGeoHits {

	private GisGeoHits() {
	}

	public static String normalizeLayer(String layer) {
		if (layer == null || layer.isBlank()) {
			return "vec";
		}
		return layer.trim().toLowerCase(Locale.ROOT);
	}

	public static Map<String, Object> poi(String name, String address, double lon, double lat, String kind) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", name == null || name.isBlank() ? kind : name);
		m.put("address", address == null ? "" : address);
		m.put("lon", lon);
		m.put("lat", lat);
		m.put("kind", kind);
		return m;
	}

	public static Map<String, Object> reverse(double lon, double lat, String address, String province,
											  String city, String county, String poi) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("lon", lon);
		data.put("lat", lat);
		data.put("address", address == null ? "" : address);
		data.put("province", province == null ? "" : province);
		data.put("city", city == null ? "" : city);
		data.put("county", county == null ? "" : county);
		data.put("poi", poi == null ? "" : poi);
		return data;
	}

	public static void merge(List<Map<String, Object>> into, List<Map<String, Object>> extra) {
		for (Map<String, Object> item : extra) {
			boolean dup = into.stream().anyMatch(exist ->
				Math.abs((Double) exist.get("lon") - (Double) item.get("lon")) < 1e-5
					&& Math.abs((Double) exist.get("lat") - (Double) item.get("lat")) < 1e-5
					&& String.valueOf(exist.get("name")).equals(String.valueOf(item.get("name"))));
			if (!dup) {
				into.add(item);
			}
		}
	}

	public static List<Map<String, Object>> cap(List<Map<String, Object>> list, int max) {
		if (list == null || list.isEmpty()) {
			return List.of();
		}
		return list.size() > max ? new ArrayList<>(list.subList(0, max)) : list;
	}

	public static double[] parseLonLat(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String[] parts = raw.trim().split("[,\\s]+");
		if (parts.length < 2) {
			return null;
		}
		try {
			return new double[] { Double.parseDouble(parts[0]), Double.parseDouble(parts[1]) };
		} catch (NumberFormatException e) {
			return null;
		}
	}

	public static String text(JsonNode n, String field) {
		JsonNode v = n.path(field);
		return v.isMissingNode() || v.isNull() ? "" : v.asText("");
	}

	/** 高德空值常是 JSON 数组 []。 */
	public static String looseText(JsonNode n, String field) {
		JsonNode v = n.path(field);
		if (v.isMissingNode() || v.isNull()) {
			return "";
		}
		if (v.isArray()) {
			return v.size() == 0 ? "" : v.get(0).asText("");
		}
		String s = v.asText("");
		return "[]".equals(s) ? "" : s;
	}

	public static String firstNonBlank(String a, String b) {
		if (a != null && !a.isBlank()) {
			return a;
		}
		return b == null ? "" : b;
	}

	public static String fmt(double v) {
		return String.format(Locale.US, "%.6f", v);
	}

	public static String escapeJson(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
