package com.mugsun.boot.gis;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规范化 FeatureCollection ↔ JTS Geometry。只认 WGS84 坐标数组，不绑业务字段。
 */
@Component
public class GisGeometryCodec {

	private final GeometryFactory factory = new GeometryFactory(new PrecisionModel(), 4326);

	public List<Geometry> fromCollection(Map<String, Object> collection) {
		List<Geometry> out = new ArrayList<>();
		Object raw = collection == null ? null : collection.get("features");
		if (!(raw instanceof List<?> list)) {
			return out;
		}
		for (Object item : list) {
			if (item instanceof Map<?, ?> feat) {
				Geometry geom = fromFeature(feat);
				if (geom != null && !geom.isEmpty()) {
					out.add(geom);
				}
			}
		}
		return out;
	}

	public Geometry fromFeature(Map<?, ?> feat) {
		Object geomObj = feat.get("geometry");
		if (!(geomObj instanceof Map<?, ?> geom)) {
			return null;
		}
		return fromGeometry(geom);
	}

	public Geometry fromGeometry(Map<?, ?> geom) {
		Object typeObj = geom.get("type");
		Object coords = geom.get("coordinates");
		if (!(typeObj instanceof String type) || coords == null) {
			return null;
		}
		try {
			return switch (type) {
				case "Point" -> factory.createPoint(xy(coords));
				case "MultiPoint" -> factory.createMultiPointFromCoords(line(coords));
				case "LineString" -> factory.createLineString(line(coords));
				case "MultiLineString" -> multiLine(coords);
				case "Polygon" -> polygon(coords);
				case "MultiPolygon" -> multiPolygon(coords);
				default -> null;
			};
		} catch (RuntimeException e) {
			return null;
		}
	}

	public Map<String, Object> toFeature(Geometry geom, Map<String, Object> props, String id) {
		Map<String, Object> feat = new LinkedHashMap<>();
		feat.put("type", "Feature");
		if (id != null && !id.isBlank()) {
			feat.put("id", id);
		}
		feat.put("properties", props == null ? new LinkedHashMap<>() : props);
		feat.put("geometry", toGeometry(geom));
		return feat;
	}

	public Map<String, Object> toGeometry(Geometry geom) {
		Map<String, Object> out = new LinkedHashMap<>();
		if (geom instanceof Point p) {
			out.put("type", "Point");
			out.put("coordinates", List.of(p.getX(), p.getY()));
			return out;
		}
		if (geom instanceof MultiPoint mp) {
			out.put("type", "MultiPoint");
			out.put("coordinates", points(mp.getCoordinates()));
			return out;
		}
		if (geom instanceof LineString ls) {
			out.put("type", "LineString");
			out.put("coordinates", points(ls.getCoordinates()));
			return out;
		}
		if (geom instanceof MultiLineString mls) {
			out.put("type", "MultiLineString");
			List<List<List<Double>>> lines = new ArrayList<>();
			for (int i = 0; i < mls.getNumGeometries(); i++) {
				lines.add(points(mls.getGeometryN(i).getCoordinates()));
			}
			out.put("coordinates", lines);
			return out;
		}
		if (geom instanceof Polygon poly) {
			out.put("type", "Polygon");
			out.put("coordinates", polygonCoords(poly));
			return out;
		}
		if (geom instanceof MultiPolygon mp) {
			out.put("type", "MultiPolygon");
			List<List<List<List<Double>>>> coords = new ArrayList<>();
			for (int i = 0; i < mp.getNumGeometries(); i++) {
				coords.add(polygonCoords((Polygon) mp.getGeometryN(i)));
			}
			out.put("coordinates", coords);
			return out;
		}
		out.put("type", "GeometryCollection");
		List<Map<String, Object>> parts = new ArrayList<>();
		for (int i = 0; i < geom.getNumGeometries(); i++) {
			parts.add(toGeometry(geom.getGeometryN(i)));
		}
		out.put("geometries", parts);
		return out;
	}

	public Geometry toMercator(Geometry geom) {
		return transform(geom, true);
	}

	public Geometry toWgs84(Geometry geom) {
		return transform(geom, false);
	}

	private Geometry transform(Geometry geom, boolean toMercator) {
		Geometry copy = geom.copy();
		copy.apply((CoordinateFilter) c -> {
			if (toMercator) {
				double[] xy = GisMercator.to3857(c.x, c.y);
				c.setX(xy[0]);
				c.setY(xy[1]);
			} else {
				double[] ll = GisMercator.to4326(c.x, c.y);
				c.setX(ll[0]);
				c.setY(ll[1]);
			}
		});
		copy.geometryChanged();
		return copy;
	}

	private Coordinate xy(Object coords) {
		List<?> pair = asList(coords);
		return new Coordinate(num(pair.get(0)), num(pair.get(1)));
	}

	private Coordinate[] line(Object coords) {
		List<?> rows = asList(coords);
		Coordinate[] out = new Coordinate[rows.size()];
		for (int i = 0; i < rows.size(); i++) {
			out[i] = xy(rows.get(i));
		}
		return out;
	}

	private MultiLineString multiLine(Object coords) {
		List<?> rows = asList(coords);
		LineString[] lines = new LineString[rows.size()];
		for (int i = 0; i < rows.size(); i++) {
			lines[i] = factory.createLineString(line(rows.get(i)));
		}
		return factory.createMultiLineString(lines);
	}

	private Polygon polygon(Object coords) {
		List<?> rings = asList(coords);
		LinearRing shell = factory.createLinearRing(closed(line(rings.get(0))));
		LinearRing[] holes = new LinearRing[Math.max(0, rings.size() - 1)];
		for (int i = 1; i < rings.size(); i++) {
			holes[i - 1] = factory.createLinearRing(closed(line(rings.get(i))));
		}
		return factory.createPolygon(shell, holes);
	}

	private MultiPolygon multiPolygon(Object coords) {
		List<?> polys = asList(coords);
		Polygon[] out = new Polygon[polys.size()];
		for (int i = 0; i < polys.size(); i++) {
			out[i] = polygon(polys.get(i));
		}
		return factory.createMultiPolygon(out);
	}

	private Coordinate[] closed(Coordinate[] ring) {
		if (ring.length >= 4 && ring[0].equals2D(ring[ring.length - 1])) {
			return ring;
		}
		Coordinate[] out = new Coordinate[ring.length + 1];
		System.arraycopy(ring, 0, out, 0, ring.length);
		out[ring.length] = new Coordinate(ring[0]);
		return out;
	}

	private List<List<List<Double>>> polygonCoords(Polygon poly) {
		List<List<List<Double>>> rings = new ArrayList<>();
		rings.add(points(poly.getExteriorRing().getCoordinates()));
		for (int i = 0; i < poly.getNumInteriorRing(); i++) {
			rings.add(points(poly.getInteriorRingN(i).getCoordinates()));
		}
		return rings;
	}

	private List<List<Double>> points(Coordinate[] coords) {
		List<List<Double>> out = new ArrayList<>(coords.length);
		for (Coordinate c : coords) {
			out.add(List.of(c.x, c.y));
		}
		return out;
	}

	private static List<?> asList(Object coords) {
		if (coords instanceof List<?> list) {
			return list;
		}
		throw new IllegalArgumentException("coordinates");
	}

	private static double num(Object v) {
		if (v instanceof Number n) {
			return n.doubleValue();
		}
		return Double.parseDouble(String.valueOf(v));
	}

	public GeometryFactory factory() {
		return factory;
	}
}
