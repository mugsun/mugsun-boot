package com.mugsun.boot.gis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKTReader;

import static org.assertj.core.api.Assertions.assertThat;

/** mugsun-boot#64：存量跨 180° 要素的 Java 侧谓词与距离 */
class GisAntimeridianTest {

	private static final GeometryFactory GF = new GeometryFactory();

	private static Geometry wkt(String s) throws Exception {
		return new WKTReader(GF).read(s);
	}

	private static Geometry box(double x0, double y0, double x1, double y1) {
		return GF.toGeometry(new Envelope(x0, x1, y0, y1));
	}

	@Test
	@DisplayName("跨 180° 线和面：0° 附近不相交，180° 两侧都相交；极冠不受影响")
	void predicates() throws Exception {
		Geometry line = wkt("LINESTRING(179.9 0, -179.9 0)");
		Geometry poly = wkt("POLYGON((179 -1, -179 -1, -179 1, 179 1, 179 -1))");
		Geometry cap = wkt("POLYGON((-180 80, -90 80, 0 80, 90 80, 180 80, 180 90, -180 90, -180 80))");
		assertThat(GisAntimeridian.wraps(line)).isTrue();
		assertThat(GisAntimeridian.wraps(poly)).isTrue();
		assertThat(GisAntimeridian.wraps(cap)).isFalse();
		assertThat(GisAntimeridian.intersects(line, box(0, -0.5, 10, 0.5))).isFalse();
		assertThat(GisAntimeridian.intersects(poly, box(0, -0.5, 10, 0.5))).isFalse();
		assertThat(GisAntimeridian.intersects(line, box(179.95, -0.5, 180, 0.5))).isTrue();
		assertThat(GisAntimeridian.intersects(line, box(-180, -0.5, -179.95, 0.5))).isTrue();
		assertThat(GisAntimeridian.contains(poly, GF.createPoint(new org.locationtech.jts.geom.Coordinate(-179.5, 0.5)))).isTrue();
		assertThat(GisAntimeridian.contains(poly, GF.createPoint(new org.locationtech.jts.geom.Coordinate(0, 0)))).isFalse();
		assertThat(GisAntimeridian.intersects(cap, box(-10, 84, 10, 86))).isTrue();
	}

	@Test
	@DisplayName("从 (0, 0) 到 180° 附近的面不再是 0 米")
	void farPolygonDistance() throws Exception {
		Geometry poly = wkt("POLYGON((179 -1, -179 -1, -179 1, 179 1, 179 -1))");
		assertThat(GisGeodesic.distanceLenient(poly, 0, 0)).isGreaterThan(19_000_000d);
		assertThat(GisGeodesic.distanceLenient(poly, -179.5, 0.5)).isZero();
		assertThat(GisGeodesic.distanceLenient(poly, 178.9, 0)).isBetween(10_000d, 12_000d);
	}
}
