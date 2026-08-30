package com.mugsun.boot.gis;

import com.mugsun.boot.security.XssCleaner;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.WKTReader;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 非 JSON 入站：WKT / CSV / KML / GPX → 与 GeoJSON Feature 同构的 Map 列表。
 */
@Component
public class GisTextIngest {

	private static final Pattern KML_COORD = Pattern.compile(
		"<coordinates[^>]*>([^<]+)</coordinates>", Pattern.CASE_INSENSITIVE);
	private static final Pattern KML_NAME = Pattern.compile(
		"<name[^>]*>([^<]*)</name>", Pattern.CASE_INSENSITIVE);
	private static final Pattern GPX_WPT = Pattern.compile(
		"<wpt\\s[^>]*lat\\s*=\\s*[\"']([^\"']+)[\"'][^>]*lon\\s*=\\s*[\"']([^\"']+)[\"']",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern GPX_WPT_SWAP = Pattern.compile(
		"<wpt\\s[^>]*lon\\s*=\\s*[\"']([^\"']+)[\"'][^>]*lat\\s*=\\s*[\"']([^\"']+)[\"']",
		Pattern.CASE_INSENSITIVE);
	/** 行首的 WKT 几何关键字：用作多几何文本的切分点 */
	private static final Pattern WKT_HEAD = Pattern.compile(
		"(?im)(?=^[ \\t]*(?:MULTI)?(?:POINT|LINESTRING|POLYGON)\\b|^[ \\t]*GEOMETRYCOLLECTION\\b)");
	private static final Pattern GPX_TRKPT = Pattern.compile(
		"<trkpt\\s[^>]*lat\\s*=\\s*[\"']([^\"']+)[\"'][^>]*lon\\s*=\\s*[\"']([^\"']+)[\"']",
		Pattern.CASE_INSENSITIVE);

	private final GeometryFactory factory = new GeometryFactory(new PrecisionModel(), 4326);
	private final GisGeometryCodec codec;

	public GisTextIngest(GisGeometryCodec codec) {
		this.codec = codec;
	}

	public List<Map<String, Object>> parse(String raw) {
		if (raw == null) {
			return List.of();
		}
		String text = raw.trim();
		if (text.isEmpty()) {
			return List.of();
		}
		String lower = text.toLowerCase(Locale.ROOT);
		if (looksWkt(text)) {
			return wkt(text);
		}
		if (lower.contains("<kml") || lower.contains("<placemark")) {
			return kml(text);
		}
		if (lower.contains("<gpx") || lower.contains("<wpt") || lower.contains("<trkpt")) {
			return gpx(text);
		}
		if (looksCsv(text)) {
			return csv(text);
		}
		return List.of();
	}

	private static boolean looksWkt(String text) {
		String head = text.length() > 24 ? text.substring(0, 24).toUpperCase(Locale.ROOT) : text.toUpperCase(Locale.ROOT);
		return head.startsWith("POINT") || head.startsWith("LINESTRING") || head.startsWith("POLYGON")
			|| head.startsWith("MULTIPOINT") || head.startsWith("MULTILINESTRING") || head.startsWith("MULTIPOLYGON")
			|| head.startsWith("GEOMETRYCOLLECTION");
	}

	private static boolean looksCsv(String text) {
		String first = text.lines().findFirst().orElse("");
		String low = first.toLowerCase(Locale.ROOT);
		return (low.contains("lon") || low.contains("lng") || low.contains("longitude") || low.contains("x"))
			&& (low.contains("lat") || low.contains("latitude") || low.contains("y"));
	}

	/**
	 * 一段文本里可以有多个 WKT：每行一个。WKTReader 只认第一个几何，所以先按行首的
	 * 几何关键字切块，再逐块解析——跨行书写的单个几何不会被切开（续行以坐标开头）。
	 */
	private List<Map<String, Object>> wkt(String text) {
		WKTReader reader = new WKTReader(factory);
		List<Map<String, Object>> out = new ArrayList<>();
		for (String chunk : WKT_HEAD.split(text)) {
			String one = chunk.trim();
			if (one.isEmpty()) {
				continue;
			}
			try {
				Geometry geom = reader.read(one);
				if (geom == null || geom.isEmpty()) {
					continue;
				}
				Map<String, Object> props = new LinkedHashMap<>();
				props.put("name", geom.getGeometryType());
				props.put("kind", kindOf(geom));
				out.add(codec.toFeature(geom, props, null));
			} catch (Exception ignored) {
				// 单块解析不了就跳过，别拖累其它几何
			}
		}
		return out;
	}

	private List<Map<String, Object>> csv(String text) {
		List<String> lines = text.lines().filter(s -> !s.isBlank()).toList();
		if (lines.size() < 2) {
			return List.of();
		}
		char sep = lines.get(0).contains("\t") ? '\t' : ',';
		String[] head = split(lines.get(0), sep);
		int lon = indexOf(head, "lon", "lng", "longitude", "x");
		int lat = indexOf(head, "lat", "latitude", "y");
		if (lon < 0 || lat < 0) {
			return List.of();
		}
		List<Map<String, Object>> out = new ArrayList<>();
		for (int i = 1; i < lines.size(); i++) {
			String[] cols = split(lines.get(i), sep);
			if (cols.length <= Math.max(lon, lat)) {
				continue;
			}
			try {
				double x = Double.parseDouble(cols[lon].trim());
				double y = Double.parseDouble(cols[lat].trim());
				Map<String, Object> props = new LinkedHashMap<>();
				for (int c = 0; c < Math.min(head.length, cols.length); c++) {
					if (c == lon || c == lat) {
						continue;
					}
					String key = XssCleaner.clean(head[c].trim());
					if (!key.isEmpty()) {
						props.put(key, XssCleaner.clean(cols[c].trim()));
					}
				}
				props.putIfAbsent("name", "点");
				props.put("kind", "point");
				Geometry pt = factory.createPoint(new Coordinate(x, y));
				out.add(codec.toFeature(pt, props, null));
			} catch (NumberFormatException ignored) {
				// skip row
			}
		}
		return out;
	}

	private List<Map<String, Object>> kml(String text) {
		List<Map<String, Object>> out = new ArrayList<>();
		Matcher names = KML_NAME.matcher(text);
		String fallbackName = names.find() ? XssCleaner.clean(names.group(1).trim()) : "KML";
		Matcher m = KML_COORD.matcher(text);
		int idx = 0;
		while (m.find()) {
			Geometry geom = parseCoordBlock(m.group(1));
			if (geom == null || geom.isEmpty()) {
				continue;
			}
			Map<String, Object> props = new LinkedHashMap<>();
			props.put("name", fallbackName + (idx == 0 ? "" : "-" + (idx + 1)));
			props.put("kind", kindOf(geom));
			out.add(codec.toFeature(geom, props, null));
			idx++;
		}
		return out;
	}

	private List<Map<String, Object>> gpx(String text) {
		List<Map<String, Object>> out = new ArrayList<>();
		collectGpx(out, GPX_WPT.matcher(text), false);
		collectGpx(out, GPX_WPT_SWAP.matcher(text), true);
		List<Coordinate> track = new ArrayList<>();
		Matcher trk = GPX_TRKPT.matcher(text);
		while (trk.find()) {
			try {
				track.add(new Coordinate(Double.parseDouble(trk.group(2)), Double.parseDouble(trk.group(1))));
			} catch (NumberFormatException ignored) {
				// skip
			}
		}
		if (track.size() >= 2) {
			Map<String, Object> props = new LinkedHashMap<>();
			props.put("name", "轨迹");
			props.put("kind", "line");
			out.add(codec.toFeature(factory.createLineString(track.toArray(Coordinate[]::new)), props, null));
		}
		return out;
	}

	private void collectGpx(List<Map<String, Object>> out, Matcher m, boolean lonFirst) {
		while (m.find()) {
			try {
				double lat = Double.parseDouble(lonFirst ? m.group(2) : m.group(1));
				double lon = Double.parseDouble(lonFirst ? m.group(1) : m.group(2));
				Map<String, Object> props = new LinkedHashMap<>();
				props.put("name", "航点");
				props.put("kind", "point");
				out.add(codec.toFeature(factory.createPoint(new Coordinate(lon, lat)), props, null));
			} catch (NumberFormatException ignored) {
				// skip
			}
		}
	}

	private Geometry parseCoordBlock(String raw) {
		String[] tuples = raw.trim().split("\\s+");
		List<Coordinate> pts = new ArrayList<>();
		for (String tuple : tuples) {
			String[] p = tuple.split(",");
			if (p.length < 2) {
				continue;
			}
			try {
				pts.add(new Coordinate(Double.parseDouble(p[0]), Double.parseDouble(p[1])));
			} catch (NumberFormatException ignored) {
				// skip
			}
		}
		if (pts.isEmpty()) {
			return null;
		}
		if (pts.size() == 1) {
			return factory.createPoint(pts.get(0));
		}
		Coordinate first = pts.get(0);
		Coordinate last = pts.get(pts.size() - 1);
		if (pts.size() >= 4 && first.equals2D(last)) {
			return factory.createPolygon(factory.createLinearRing(pts.toArray(Coordinate[]::new)));
		}
		return factory.createLineString(pts.toArray(Coordinate[]::new));
	}

	private static String[] split(String line, char sep) {
		return line.split(String.valueOf(sep), -1);
	}

	private static int indexOf(String[] head, String... keys) {
		for (int i = 0; i < head.length; i++) {
			String h = head[i].trim().toLowerCase(Locale.ROOT);
			for (String key : keys) {
				if (h.equals(key)) {
					return i;
				}
			}
		}
		return -1;
	}

	private static String kindOf(Geometry geom) {
		String t = geom.getGeometryType();
		if (t.contains("Point")) {
			return "point";
		}
		if (t.contains("Line")) {
			return "line";
		}
		return "polygon";
	}
}
