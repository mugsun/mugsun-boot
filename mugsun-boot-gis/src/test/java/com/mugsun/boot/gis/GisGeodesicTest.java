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

/** 回落路径米制缓冲 / 量距精度：北京、赤道、70°N 以上 × 点线面 × 500 m / 20 km / 200 km，对照 WGS84 椭球测地距离（GeographicLib）。 */
class GisGeodesicTest {

	static final double TOL = 0.01d;
	/** 椭球投影后缓冲误差的断言上限：0.1%（自写球面 AEQD 时为 0.5%） */
	static final double BUF_TOL = 0.001d;

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
		double[][] places = { { 116.4, 39.9 }, { 30.0, 0.0 }, { 25.0, 72.0 }, { 15.0, 78.2 }, { 0.0, 86.0 },
			{ 60.0, -88.0 } };
		String[] names = { "北京 39.9°N", "赤道 0°", "高纬 72°N", "高纬 78.2°N", "极区 86°N", "极区 88°S" };
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
	@DisplayName("缓冲边界到原要素的测地距离误差 < 0.1%（含 86°N、88°S 极区）")
	void bufferWithinOnePercent(String place, String kind, Geometry geom, double meters) {
		Geometry buf = GisGeodesic.buffer(geom, meters);
		double step = Math.min(0.0005, meters / 111_000d / 20);
		double[] err = GeodesicTruth.relErrorRange(geom, buf, meters, step);
		System.out.printf("GEO|%s|%s|%.0f|%+.3f%%|%+.3f%%%n", place, kind, meters, err[0] * 100, err[1] * 100);
		assertThat(err[0]).isGreaterThan(-BUF_TOL);
		assertThat(err[1]).isLessThan(BUF_TOL);
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
			double far = GeodesicTruth.inverse(lon, lat, lon + 0.05, lat + 0.03);
			assertThat(GisGeodesic.distance(point(lon + 0.05, lat + 0.03), lon, lat) / far - 1).isBetween(-TOL, TOL);
			assertThat(GisGeodesic.distance(point(lon, lat), point(lon + 0.05, lat + 0.03)) / far - 1)
				.isBetween(-TOL, TOL);
			// 点在面内 → 0
			Geometry poly = polygon(lon, lat);
			assertThat(GisGeodesic.distance(poly, poly.getCentroid().getX(), poly.getCentroid().getY())).isZero();
			// 线段中部穿过查询点附近：距离取线上最近点而不是顶点；边按测地线（与 PostGIS geography 一致），真值沿测地线取点
			Geometry seg = GF.createLineString(new Coordinate[] { new Coordinate(lon - 0.1, lat),
				new Coordinate(lon + 0.1, lat) });
			double d = GisGeodesic.distance(seg, lon, lat + 0.001);
			assertThat(d / GeodesicTruth.minDistance(seg, lon, lat + 0.001) - 1).isBetween(-TOL, TOL);
			// 长度、面积
			Geometry square = polygon(lon, lat);
			Coordinate[] c = square.getCoordinates();
			double w = GeodesicTruth.inverse(c[0].x, c[0].y, c[1].x, c[1].y);
			double h = GeodesicTruth.inverse(c[1].x, c[1].y, c[2].x, c[2].y);
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
	@DisplayName("极区可算（不再有 85° 限制）；缓冲包住极点、单个部件宽于 180° 才抛业务异常")
	void polarComputableExceptPoleAndAntimeridian() {
		assertThat(GisGeodesic.buffer(point(0, 85.5), 100d).isEmpty()).isFalse();
		assertThat(GisGeodesic.buffer(point(0, 84.5), 100_000d).isEmpty()).isFalse();
		assertThat(GisGeodesic.buffer(point(0, -86), 10d).isEmpty()).isFalse();
		// 查询点在 89°、北极点本身：距离照常
		double d89 = GisGeodesic.distance(point(10, 88), 0, 89);
		assertThat(d89 / GeodesicTruth.inverse(0, 89, 10, 88) - 1).isBetween(-BUF_TOL, BUF_TOL);
		double dPole = GisGeodesic.distance(point(10, 88), 0, 90);
		assertThat(dPole / GeodesicTruth.inverse(0, 90, 10, 88) - 1).isBetween(-1e-6, 1e-6);
		// 面积：86° 附近约 2 km 见方，与测地边长乘积相差 < 1%
		assertThat(GisGeodesic.area(polygon(10, 86))).isBetween(3_000_000d, 4_500_000d);
		// 缓冲包住极点：(0, 89.995) 离极点约 556 m，1 km 缓冲包住北极；南极同理
		assertThatThrownBy(() -> GisGeodesic.buffer(point(0, 89.995), 1000d))
			.isInstanceOf(ServiceException.class).hasMessageContaining("极点");
		assertThatThrownBy(() -> GisGeodesic.buffer(point(120, -89.999), 500d))
			.isInstanceOf(ServiceException.class).hasMessageContaining("极点");
		assertThatThrownBy(() -> GisGeodesic.buffer(point(0, 90), 10d))
			.isInstanceOf(ServiceException.class).hasMessageContaining("极点");
		// 单个部件不跨 180° 却宽于 180°：走向无法确定
		Geometry wide = GF.createLineString(new Coordinate[] {
			new Coordinate(-100, 0), new Coordinate(0, 0), new Coordinate(100, 0) });
		assertThatThrownBy(() -> GisGeodesic.length(wide))
			.isInstanceOf(ServiceException.class).hasMessageContaining("180°");
		assertThatThrownBy(() -> GisGeodesic.buffer(wide, 10d))
			.isInstanceOf(ServiceException.class).hasMessageContaining("180°");
	}

	@ParameterizedTest(name = "{0} {2}m")
	@MethodSource("polarShapes")
	@DisplayName("纬度 88–90° 的线和面（长边、跨大经度）缓冲：边界到原要素的测地距离误差 < 0.1%")
	void polarLineAndPolygonBuffer(String name, Geometry geom, double meters) {
		Geometry buf = GisGeodesic.buffer(geom, meters);
		double[] err = GeodesicTruth.relErrorRange(geom, buf, meters, Math.min(0.0005, meters / 111_000d / 20));
		System.out.printf("POLAR|%s|%.0f|%+.4f%%|%+.4f%%%n", name, meters, err[0] * 100, err[1] * 100);
		assertThat(err[0]).isGreaterThan(-BUF_TOL);
		assertThat(err[1]).isLessThan(BUF_TOL);
	}

	static Stream<Arguments> polarShapes() {
		Geometry line = GF.createLineString(new Coordinate[] { new Coordinate(10, 89.5), new Coordinate(100, 89.6),
			new Coordinate(170, 89.2) });
		Geometry south = GF.createLineString(new Coordinate[] { new Coordinate(-60, -88), new Coordinate(60, -88.5) });
		Geometry poly = GF.createPolygon(new Coordinate[] { new Coordinate(0, 88), new Coordinate(40, 88),
			new Coordinate(40, 89), new Coordinate(0, 89), new Coordinate(0, 88) });
		Geometry wide = GF.createPolygon(new Coordinate[] { new Coordinate(-80, -88.2), new Coordinate(80, -88.2),
			new Coordinate(80, -89.6), new Coordinate(-80, -89.6), new Coordinate(-80, -88.2) });
		List<Arguments> out = new java.util.ArrayList<>();
		for (double m : new double[] { 500d, 5_000d, 20_000d }) {
			out.add(Arguments.of("线 88–88.5°S 跨 120°", south, m));
			out.add(Arguments.of("面 88–89°N 40°宽", poly, m));
		}
		// 这两个离极点不到 50 km：20 km 缓冲会包住极点或绕过 180°，只测 500 m / 5 km
		for (double m : new double[] { 500d, 5_000d }) {
			out.add(Arguments.of("线 89.2–89.6°N 跨 160°", line, m));
			out.add(Arguments.of("面 88.2–89.6°S 160°宽", wide, m));
		}
		return out.stream();
	}

	@Test
	@DisplayName("面积用 GeographicLib PolygonArea：与环方向无关、扣除内环；极区长边面积大于按经纬度直边算的面积")
	void polygonAreaGeodesicEdges() throws Exception {
		org.locationtech.jts.io.WKTReader r = new org.locationtech.jts.io.WKTReader(GF);
		Geometry ccw = r.read("POLYGON ((0 20, 60 20, 60 80, 0 80, 0 20))");
		Geometry cw = r.read("POLYGON ((0 20, 0 80, 60 80, 60 20, 0 20))");
		assertThat(GisGeodesic.area(cw)).isEqualTo(GisGeodesic.area(ccw));
		Geometry holed = r.read("POLYGON ((0 20, 60 20, 60 80, 0 80, 0 20), (10 30, 20 30, 20 40, 10 40, 10 30))");
		Geometry hole = r.read("POLYGON ((10 30, 20 30, 20 40, 10 40, 10 30))");
		assertThat(GisGeodesic.area(holed)).isCloseTo(GisGeodesic.area(ccw) - GisGeodesic.area(hole),
			org.assertj.core.data.Offset.offset(1d));
		// 高纬 170° 宽：测地线边向极点弯（75° 那条边最高弯到 89° 附近），面积远小于纬线边围成的带
		Geometry wide = r.read("POLYGON ((0 75, 170 75, 170 88, 0 88, 0 75))");
		double a = 6378137d, band = 2 * Math.PI * a * a * (Math.sin(Math.toRadians(88)) - Math.sin(Math.toRadians(75)))
			* 170 / 360;
		assertThat(GisGeodesic.area(wide)).isLessThan(band / 10);
	}

	@Test
	@DisplayName("跨 180°（RFC 7946 §3.1.9）：越过 180° 的缓冲切成两块且面积不变，179↔-179 的线走短路")
	void antimeridianBufferIsCut() {
		Geometry cut = GisGeodesic.buffer(point(179.95, 10), 20_000d);
		assertThat(cut.getGeometryType()).isEqualTo("MultiPolygon");
		assertThat(cut.getNumGeometries()).isEqualTo(2);
		org.locationtech.jts.geom.Envelope e = cut.getEnvelopeInternal();
		assertThat(e.getMinX()).isEqualTo(-180d);
		assertThat(e.getMaxX()).isEqualTo(180d);
		double same = GisGeodesic.area(GisGeodesic.buffer(point(9.95, 10), 20_000d));
		assertThat(GisGeodesic.area(cut) / same - 1).isBetween(-1e-4, 1e-4);
		// 179.999° 的 500 m 缓冲同样切开
		assertThat(GisGeodesic.buffer(point(179.999, 0), 500d).getNumGeometries()).isEqualTo(2);
		Geometry cross = GF.createLineString(new Coordinate[] { new Coordinate(179, 0), new Coordinate(-179, 0) });
		assertThat(GisGeodesic.length(cross) / GeodesicTruth.inverse(179, 0, -179, 0) - 1).isBetween(-1e-9, 1e-9);
		// 查询点在线的近对蹠处：AEQD 在对蹠点附近变形最大，平面最近点略偏近，实测 −0.07%
		assertThat(GisGeodesic.distance(cross, 0, 0) / GeodesicTruth.inverse(0, 0, 179, 0) - 1).isBetween(-1e-3, 1e-6);
		assertThat(GisGeodesic.distance(cross, -179.5, 0.5) / GeodesicTruth.inverse(-179.5, 0.5, -179.5, 0) - 1)
			.isBetween(-1e-3, 1e-3);
	}

	@Test
	@DisplayName("RFC 7946 §3.1.9 示例：在 180° 拆开的 MultiLineString / MultiPolygon 按一个整体计算")
	void rfc7946Examples() throws Exception {
		org.locationtech.jts.io.WKTReader r = new org.locationtech.jts.io.WKTReader(GF);
		Geometry ml = r.read("MULTILINESTRING ((170 45, 180 45), (-180 45, -170 45))");
		double expectLen = GeodesicTruth.inverse(170, 45, 180, 45) + GeodesicTruth.inverse(-180, 45, -170, 45);
		assertThat(GisGeodesic.length(ml) / expectLen - 1).isBetween(-1e-9, 1e-9);
		Geometry mp = r.read("MULTIPOLYGON (((180 40, 180 50, 170 50, 170 40, 180 40)),"
			+ " ((-170 40, -170 50, -180 50, -180 40, -170 40)))");
		Geometry same = r.read("MULTIPOLYGON (((0 40, 0 50, -10 50, -10 40, 0 40)), ((10 40, 10 50, 0 50, 0 40, 10 40)))");
		assertThat(GisGeodesic.area(mp) / GisGeodesic.area(same) - 1).isBetween(-1e-4, 1e-4);
		assertThat(GisGeodesic.distance(mp, -175, 45)).isZero();
		// 离两块都有距离的点：取各部件最小值
		double d = GisGeodesic.distance(mp, -165, 45);
		assertThat(d / GeodesicTruth.inverse(-165, 45, -170, 45) - 1).isBetween(-0.01, 0.001);
		Geometry buf = GisGeodesic.buffer(mp, 1000d);
		assertThat(buf.getNumGeometries()).isEqualTo(2);
		assertThat(GisGeodesic.distance(buf, 179.999, 45)).isZero();
		// 两几何间距离：跨 180° 两侧取短路
		assertThat(GisGeodesic.distance(point(179.9, 0), point(-179.9, 0)) / GeodesicTruth.inverse(179.9, 0, -179.9, 0) - 1)
			.isBetween(-1e-6, 1e-6);
	}

	@Test
	@DisplayName("斐济：跨 180° 的单个面（环内相邻顶点跨经线）面积与等大的普通面一致，缓冲切成两块")
	void fijiPolygon() throws Exception {
		org.locationtech.jts.io.WKTReader r = new org.locationtech.jts.io.WKTReader(GF);
		Geometry fiji = r.read("POLYGON ((177 -16, -179 -16, -179 -19, 177 -19, 177 -16))");
		Geometry same = r.read("POLYGON ((0 -16, 4 -16, 4 -19, 0 -19, 0 -16))");
		assertThat(GisGeodesic.area(fiji) / GisGeodesic.area(same) - 1).isBetween(-1e-4, 1e-4);
		assertThat(GisGeodesic.distance(fiji, 179.5, -17.5)).isZero();
		Geometry buf = GisGeodesic.buffer(fiji, 5000d);
		assertThat(buf.getNumGeometries()).isEqualTo(2);
		assertThat(buf.getEnvelopeInternal().getWidth()).isEqualTo(360d);
	}

	@Test
	@DisplayName("投影选择：缓冲 / 量距 88° 以上用极点 AEQD，其余用包络中心 AEQD（原点不取整）；lon_0 接近 180° 也能正反算")
	void projectionSelection() {
		assertThat(GisProjection.forDistance(new org.locationtech.jts.geom.Envelope(0, 1, -89, -88.5)).originLat())
			.isEqualTo(-90d);
		assertThat(GisProjection.forDistance(new org.locationtech.jts.geom.Envelope(0.004, 0.006, 10.001, 10.003))
			.originLon()).isEqualTo(0.005d);
		GisProjection e = GisProjection.aeqd(179.9, 0);
		double[] w = e.forward(-179.9, 0);
		assertThat(w[0] / GeodesicTruth.inverse(179.9, 0, -179.9, 0) - 1).isBetween(-1e-9, 1e-9);
		assertThat(e.inverse(w[0], w[1])[0]).isCloseTo(180.1, org.assertj.core.data.Offset.offset(1e-9));
		GisProjection n = GisProjection.forDistance(new org.locationtech.jts.geom.Envelope(0, 1, 88.5, 89));
		assertThat(n.originLat()).isEqualTo(90d);
		assertThat(GisProjection.forDistance(new org.locationtech.jts.geom.Envelope(0, 1, 80, 81)).originLat())
			.isEqualTo(80.5d);
		GisProjection c = GisProjection.forDistance(new org.locationtech.jts.geom.Envelope(116, 117, 39, 40));
		assertThat(c.originLon()).isEqualTo(116.5d);
		// 椭球 AEQD：原点到任意点的平面距离等于测地距离
		GisProjection p = GisProjection.aeqd(116.4, 39.9);
		double[] xy = p.forward(121.47, 31.23);
		assertThat(Math.hypot(xy[0], xy[1]) / GeodesicTruth.inverse(116.4, 39.9, 121.47, 31.23) - 1)
			.isBetween(-1e-9, 1e-9);
		double[] back = p.inverse(xy[0], xy[1]);
		assertThat(back[0]).isCloseTo(121.47, org.assertj.core.data.Offset.offset(1e-9));
		assertThat(back[1]).isCloseTo(31.23, org.assertj.core.data.Offset.offset(1e-9));
	}

	@Test
	@DisplayName("测地距离用 GeographicLib：赤道上的对蹠点等于半个子午圈长度（WGS84 20003931.4586 m），近对蹠点也收敛")
	void geographicLibInverse() {
		// Vincenty 不收敛的近对蹠点（旧实现要回落大圆）：有限且在半个子午圈附近
		double nearAnti = GisGeodesic.inverse(0, 0, 179.7, 0.3);
		assertThat(nearAnti).isFinite().isBetween(19_900_000d, 20_100_000d);
		assertThat(GisGeodesic.inverse(0, 0, 180, 0)).isCloseTo(20_003_931.46, org.assertj.core.data.Offset.offset(0.01));
		assertThat(GisGeodesic.inverse(10, 10, 10, 10)).isZero();
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

	@Test
	@DisplayName("预筛包络越过 ±180° 时拆成两个框，靠近极点时退化为全经度")
	void looseEnvelopesSplitAtAntimeridian() {
		List<org.locationtech.jts.geom.Envelope> east = GisGeodesic.looseEnvelopes(179.9, 0, 30_000d);
		assertThat(east).hasSize(2);
		assertThat(east).anyMatch(e -> e.contains(-179.9, 0));
		assertThat(east).anyMatch(e -> e.contains(179.95, 0));
		List<org.locationtech.jts.geom.Envelope> west = GisGeodesic.looseEnvelopes(-179.9, 0, 30_000d);
		assertThat(west).hasSize(2).anyMatch(e -> e.contains(179.9, 0));
		assertThat(GisGeodesic.looseEnvelopes(20, 10, 30_000d)).hasSize(1);
		assertThat(GisGeodesic.looseEnvelopes(20, 84.99, 200_000d)).hasSize(1)
			.allMatch(e -> e.getMaxY() <= 90d && e.contains(20, 85.5));
		assertThat(GisGeodesic.looseEnvelopes(20, 89.99, 200_000d)).hasSize(1).allMatch(e -> e.getWidth() == 360d);
	}

	@Test
	@DisplayName("图层查询用的宽松距离 / 缓冲：包住极点、跨 180° 的要素不抛异常")
	void lenientVariants() {
		// 极区点：直接按严格路径计算
		double d = GisGeodesic.distanceLenient(point(20, 86), 20, 80);
		assertThat(d / GeodesicTruth.inverse(20, 80, 20, 86) - 1).isBetween(-0.001, 0.001);
		// 正常要素：与严格版完全相同
		assertThat(GisGeodesic.distanceLenient(point(21, 40), 20, 40))
			.isEqualTo(GisGeodesic.distance(point(21, 40), 20, 40));
		// 线段中部最近（非顶点）：椭球测地距离算任意最近点对，对照真值 < 0.5%
		Geometry seg = GF.createLineString(new Coordinate[] { new Coordinate(19, 41), new Coordinate(21, 41) });
		double mid = GisGeodesic.distance(seg, 20, 40);
		assertThat(mid / GeodesicTruth.inverse(20, 40, 20, 41) - 1).isBetween(-0.005, 0.005);
		assertThat(GisGeodesic.distanceLenient(GF.createPolygon(), 0, 0)).isEqualTo(Double.POSITIVE_INFINITY);
		assertThat(GisGeodesic.tryBuffer(point(0, 89.995), 1000d)).isNull();
		assertThat(GisGeodesic.tryBuffer(point(0, 86), 100d)).isNotNull();
		// 越过 180° 的缓冲不再跳过，切成两块（RFC 7946）
		assertThat(GisGeodesic.tryBuffer(point(179.95, 0), 20_000d).getNumGeometries()).isEqualTo(2);
		assertThat(GisGeodesic.tryBuffer(point(0, 0), 100d).isEmpty()).isFalse();
	}

	@Test
	@DisplayName("跨 180° 要素的宽松距离：负经度加 360 后再加密，不绕地球走长路（评审实测的两个误差用例）")
	void lenientAntimeridianFeature() {
		Geometry cross = GF.createLineString(new Coordinate[] { new Coordinate(179.9, 0), new Coordinate(-179.9, 0) });
		assertThat(GisGeodesic.crossesAntimeridian(cross)).isTrue();
		double far = GisGeodesic.distanceLenient(cross, 0, 0);
		System.out.printf("LENIENT|(0,0)|%.1f m%n", far);
		// 最近点是 (±179.9, 0)，大圆约 179.9° × 111.195 km ≈ 20004 km，绝不能是 0
		assertThat(far).isBetween(19_900_000d, 20_100_000d);
		double near = GisGeodesic.distanceLenient(cross, 179.95, 0.1);
		double truth = GeodesicTruth.inverse(179.95, 0.1, 179.95, 0);
		System.out.printf("LENIENT|(179.95,0.1)|%.1f m|truth %.1f m|err %+.3f%%%n", near, truth, (near / truth - 1) * 100);
		assertThat(near / truth - 1).isBetween(-0.01, 0.01);
		// 西侧查询点换到同一经度范围
		double west = GisGeodesic.distanceLenient(cross, -179.95, -0.1);
		assertThat(west / truth - 1).isBetween(-0.01, 0.01);
		// 跨 180° 的面：查询点落在面内为 0
		Geometry box = GF.createPolygon(new Coordinate[] { new Coordinate(179, -1), new Coordinate(-179, -1),
			new Coordinate(-179, 1), new Coordinate(179, 1), new Coordinate(179, -1) });
		assertThat(GisGeodesic.distanceLenient(box, -179.5, 0)).isZero();
		assertThat(GisGeodesic.distanceLenient(box, 179.5, 0)).isZero();
		double out = GisGeodesic.distanceLenient(box, 178, 0);
		assertThat(out / GeodesicTruth.inverse(178, 0, 179, 0) - 1).isBetween(-0.01, 0.01);
	}
}
