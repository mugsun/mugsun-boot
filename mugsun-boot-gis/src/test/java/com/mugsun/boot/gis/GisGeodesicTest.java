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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mugsun.core.tool.exception.ServiceException;

/** 回落路径米制缓冲 / 量距精度：北京、赤道、70°N 以上 × 点线面 × 500 m / 20 km / 200 km，对照 WGS84 Vincenty 测地距离。 */
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
			for (double m : new double[] { 500d, 20_000d, 200_000d }) {
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

	@Test
	@DisplayName("空几何：距离 +∞，缓冲为空，面积 / 长度为 0，不抛 NPE")
	void emptyGeometry() {
		Geometry empty = GF.createPolygon();
		assertThat(GisGeodesic.distance(empty, 116.4, 39.9)).isEqualTo(Double.POSITIVE_INFINITY);
		assertThat(GisGeodesic.distance(empty, point(116.4, 39.9))).isEqualTo(Double.POSITIVE_INFINITY);
		assertThat(GisGeodesic.distance(null, point(116.4, 39.9))).isEqualTo(Double.POSITIVE_INFINITY);
		assertThat(GisGeodesic.buffer(empty, 100d).isEmpty()).isTrue();
		assertThat(GisGeodesic.buffer(GF.createLineString(), 100d).isEmpty()).isTrue();
		assertThat(GisGeodesic.area(empty)).isZero();
		assertThat(GisGeodesic.length(GF.createLineString())).isZero();
	}

	@Test
	@DisplayName("极区（|纬度| > 85°，含缓冲外扩）与跨 180° 经线：抛业务异常并给出提示")
	void polarAndAntimeridianRejected() {
		assertThatThrownBy(() -> GisGeodesic.buffer(point(0, 85.5), 100d))
			.isInstanceOf(ServiceException.class).hasMessageContaining("极区");
		// 84.5° 的点本身合法，但 100 km 缓冲会伸进 85° 以北
		assertThatThrownBy(() -> GisGeodesic.buffer(point(0, 84.5), 100_000d))
			.isInstanceOf(ServiceException.class).hasMessageContaining("极区");
		assertThatThrownBy(() -> GisGeodesic.buffer(point(0, -86), 10d))
			.isInstanceOf(ServiceException.class).hasMessageContaining("极区");
		assertThatThrownBy(() -> GisGeodesic.distance(point(10, 10), 0, 89))
			.isInstanceOf(ServiceException.class).hasMessageContaining("极区");
		assertThatThrownBy(() -> GisGeodesic.area(polygon(10, 86)))
			.isInstanceOf(ServiceException.class).hasMessageContaining("极区");
		// 179.95° 的点做 20 km 缓冲会越过 180°
		assertThatThrownBy(() -> GisGeodesic.buffer(point(179.95, 10), 20_000d))
			.isInstanceOf(ServiceException.class).hasMessageContaining("180°");
		// 179° 与 -179° 连线被读成绕地球一整圈
		Geometry cross = GF.createLineString(new Coordinate[] { new Coordinate(179, 0), new Coordinate(-179, 0) });
		assertThatThrownBy(() -> GisGeodesic.length(cross))
			.isInstanceOf(ServiceException.class).hasMessageContaining("180°");
		assertThatThrownBy(() -> GisGeodesic.distance(cross, 0, 0))
			.isInstanceOf(ServiceException.class).hasMessageContaining("180°");
		// 84.9° 的点做小缓冲仍可用
		assertThat(GisGeodesic.buffer(point(0, 84.9), 100d).isEmpty()).isFalse();
	}

	@Test
	@DisplayName("Vincenty：与测试侧实现相差 < 1 cm；近对蹠点不收敛时回落大圆，结果有限且量级正确")
	void vincentyAndFallback() {
		assertThat(GisGeodesic.vincenty(116.4, 39.9, 121.47, 31.23))
			.isCloseTo(GeodesicTruth.vincenty(116.4, 39.9, 121.47, 31.23), org.assertj.core.data.Offset.offset(0.01));
		double nearAnti = GisGeodesic.vincenty(0, 0, 179.7, 0.3);
		assertThat(nearAnti).isFinite().isBetween(19_900_000d, 20_100_000d);
		assertThat(GisGeodesic.greatCircle(0, 0, 179.7, 0.3) / nearAnti - 1).isBetween(-0.01, 0.01);
		assertThat(GisGeodesic.vincenty(10, 10, 10, 10)).isZero();
	}

	@Test
	@DisplayName("宽松包络只多不漏：包络外的点一定在半径之外")
	void looseEnvelopeNeverMisses() {
		for (double lat : new double[] { 0, 39.9, 72, 84 }) {
			for (double m : new double[] { 1_000d, 50_000d, 200_000d }) {
				org.locationtech.jts.geom.Envelope box = GisGeodesic.looseEnvelope(20, lat, m);
				for (int az = 0; az < 360; az += 15) {
					// 沿方位角走 0.999·m 的点必须落在包络内
					double[] p = destination(20, lat, az, m * 0.999);
					assertThat(box.contains(p[0], p[1])).as("lat=%s m=%s az=%s", lat, m, az).isTrue();
				}
			}
		}
	}

	/** 球面正算（平均半径，略高估纬度跨度，足以检验包络宽松性） */
	private static double[] destination(double lon, double lat, double azDeg, double m) {
		double r = 6_356_752d;
		double d = m / r;
		double phi1 = Math.toRadians(lat);
		double az = Math.toRadians(azDeg);
		double phi2 = Math.asin(Math.sin(phi1) * Math.cos(d) + Math.cos(phi1) * Math.sin(d) * Math.cos(az));
		double lam = Math.toRadians(lon) + Math.atan2(Math.sin(az) * Math.sin(d) * Math.cos(phi1),
			Math.cos(d) - Math.sin(phi1) * Math.sin(phi2));
		return new double[] { Math.toDegrees(lam), Math.toDegrees(phi2) };
	}
}
