package com.mugsun.boot.gis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.operation.buffer.BufferOp;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.mugsun.boot.gis.GeodesicTruth.GF;
import static org.assertj.core.api.Assertions.assertThat;

/** 回落路径米制缓冲 / 量距精度：北京、赤道、70°N 以上 × 点线面 × 近远两档距离，对照 WGS84 Vincenty 测地距离。 */
class GisGeodesicTest {

	static final double TOL = 0.01d;

	static Geometry point(double lon, double lat) {
		return GF.createPoint(new Coordinate(lon, lat));
	}

	/** 约 3 km 的东北向折线 */
	static Geometry line(double lon, double lat) {
		double dLat = 0.02, dLon = 0.02 / Math.cos(Math.toRadians(lat));
		return GF.createLineString(new Coordinate[] { new Coordinate(lon, lat),
			new Coordinate(lon + dLon, lat + dLat / 2), new Coordinate(lon + dLon * 1.5, lat + dLat) });
	}

	/** 约 2 km 见方 */
	static Geometry polygon(double lon, double lat) {
		double dLat = 0.018, dLon = 0.018 / Math.cos(Math.toRadians(lat));
		return GF.createPolygon(new Coordinate[] { new Coordinate(lon, lat), new Coordinate(lon + dLon, lat),
			new Coordinate(lon + dLon, lat + dLat), new Coordinate(lon, lat + dLat), new Coordinate(lon, lat) });
	}

	static Stream<Arguments> cases() {
		List<Arguments> out = new ArrayList<>();
		double[][] places = { { 116.4, 39.9 }, { 30.0, 0.0 }, { 25.0, 72.0 }, { 15.0, 78.2 } };
		String[] names = { "北京 39.9°N", "赤道 0°", "高纬 72°N", "高纬 78.2°N" };
		for (int i = 0; i < places.length; i++) {
			double lon = places[i][0], lat = places[i][1];
			for (double m : new double[] { 500d, 20_000d }) {
				out.add(Arguments.of(names[i], "点", point(lon, lat), m));
				out.add(Arguments.of(names[i], "线", line(lon, lat), m));
				out.add(Arguments.of(names[i], "面", polygon(lon, lat), m));
			}
		}
		return out.stream();
	}

	@ParameterizedTest(name = "{0} {1} {3}m")
	@MethodSource("cases")
	@DisplayName("缓冲边界到原要素的测地距离误差 < 1%")
	void bufferWithinOnePercent(String place, String kind, Geometry geom, double meters) {
		Geometry buf = GisGeodesic.buffer(geom, meters);
		double step = Math.min(0.0005, meters / 111_000d / 20);
		double[] err = GeodesicTruth.relErrorRange(geom, buf, meters, step);
		System.out.printf("GEO|%s|%s|%.0f|%+.3f%%|%+.3f%%%n", place, kind, meters, err[0] * 100, err[1] * 100);
		assertThat(err[0]).isGreaterThan(-TOL);
		assertThat(err[1]).isLessThan(TOL);
	}

	@Test
	@DisplayName("对照：旧的 Web Mercator 缓冲在高纬严重偏小")
	void oldMercatorBufferWasWrong() {
		for (double lat : new double[] { 0.0, 39.9, 72.0 }) {
			Geometry p = point(20.0, lat);
			GisGeometryCodec codec = new GisGeometryCodec();
			Geometry old = codec.toWgs84(BufferOp.bufferOp(codec.toMercator(p), 1000d));
			double[] err = GeodesicTruth.relErrorRange(p, old, 1000d, 0.001);
			System.out.printf("OLD|%.1f|%+.1f%%|%+.1f%%%n", lat, err[0] * 100, err[1] * 100);
		}
		Geometry p = point(116.4, 39.9);
		GisGeometryCodec codec = new GisGeometryCodec();
		Geometry old = codec.toWgs84(BufferOp.bufferOp(codec.toMercator(p), 1000d));
		double[] err = GeodesicTruth.relErrorRange(p, old, 1000d, 0.001);
		assertThat(err[1]).isLessThan(-0.2);
	}

	@Test
	@DisplayName("点到几何距离、两几何距离、面积、长度对照测地真值")
	void distanceAreaLength() {
		for (double lat : new double[] { 0.0, 39.9, 72.0 }) {
			double lon = 20.0;
			double far = GeodesicTruth.vincenty(lon, lat, lon + 0.05, lat + 0.03);
			assertThat(GisGeodesic.distance(point(lon + 0.05, lat + 0.03), lon, lat) / far - 1).isBetween(-TOL, TOL);
			assertThat(GisGeodesic.distance(point(lon, lat), point(lon + 0.05, lat + 0.03)) / far - 1)
				.isBetween(-TOL, TOL);
			// 点在面内 → 0
			Geometry poly = polygon(lon, lat);
			assertThat(GisGeodesic.distance(poly, poly.getCentroid().getX(), poly.getCentroid().getY())).isZero();
			// 线段中部穿过查询点附近：距离取线上最近点而不是顶点
			Geometry seg = GF.createLineString(new Coordinate[] { new Coordinate(lon - 0.1, lat),
				new Coordinate(lon + 0.1, lat) });
			double d = GisGeodesic.distance(seg, lon, lat + 0.001);
			assertThat(d / GeodesicTruth.vincenty(lon, lat, lon, lat + 0.001) - 1).isBetween(-TOL, TOL);
			// 长度、面积
			Geometry square = polygon(lon, lat);
			Coordinate[] c = square.getCoordinates();
			double w = GeodesicTruth.vincenty(c[0].x, c[0].y, c[1].x, c[1].y);
			double h = GeodesicTruth.vincenty(c[1].x, c[1].y, c[2].x, c[2].y);
			assertThat(GisGeodesic.area(square) / (w * h) - 1).isBetween(-TOL, TOL);
			assertThat(GisGeodesic.length(square.getBoundary()) / (2 * w + 2 * h) - 1).isBetween(-TOL, TOL);
		}
	}
}
