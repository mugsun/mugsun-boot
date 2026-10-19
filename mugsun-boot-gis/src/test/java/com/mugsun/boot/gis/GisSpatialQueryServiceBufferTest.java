package com.mugsun.boot.gis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.gis.entity.GisLayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.locationtech.jts.geom.Geometry;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mugsun.core.tool.exception.ServiceException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 服务级回落路径：PostGIS 不可用 与 forceJava 两条入口，缓冲、半径、最近邻都按地面米。 */
class GisSpatialQueryServiceBufferTest {

	private final GisGeometryCodec codec = new GisGeometryCodec();
	private final ObjectMapper om = new ObjectMapper();

	private GisSpatialQueryService service(boolean postgisUp) {
		GisSpatialSupport support = mock(GisSpatialSupport.class);
		when(support.available()).thenReturn(postgisUp);
		return new GisSpatialQueryService(support, mock(GisFeatureStore.class), codec, om);
	}

	private GisLayer layer(String geometryJson) {
		GisLayer l = new GisLayer();
		l.setId(1L);
		l.setKind("vector");
		l.setDataJson("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"id\":\"f1\","
			+ "\"properties\":{},\"geometry\":" + geometryJson + "}]}");
		return l;
	}

	@SuppressWarnings("unchecked")
	private Geometry firstGeom(Map<String, Object> out) {
		Map<String, Object> fc = (Map<String, Object>) out.getOrDefault("collection", out);
		List<Map<String, Object>> feats = (List<Map<String, Object>>) fc.get("features");
		return codec.fromGeometry((Map<?, ?>) feats.get(0).get("geometry"));
	}

	@ParameterizedTest(name = "{0} postgis={1} forceJava={2}")
	@CsvSource({
		"116.4, 39.9, false, false", "116.4, 39.9, true, true",
		"30.0, 0.0, false, false", "30.0, 0.0, true, true",
		"25.0, 72.0, false, false", "25.0, 72.0, true, true" })
	@DisplayName("Java 回落缓冲（无 PostGIS / forceJava）误差 < 1%")
	void bufferFallback(double lon, double lat, boolean postgisUp, boolean forceJava) {
		GisSpatialQueryService svc = service(postgisUp);
		Map<String, Object> out = svc.buffer(layer("{\"type\":\"Point\",\"coordinates\":[" + lon + "," + lat + "]}"),
			2000d, 10, forceJava);
		assertThat(out.get("engine")).isEqualTo(GisConstants.ENGINE_JAVA);
		Geometry buf = firstGeom(out);
		double[] err = GeodesicTruth.relErrorRange(GeodesicTruth.GF.createPoint(
			new org.locationtech.jts.geom.Coordinate(lon, lat)), buf, 2000d, 0.001);
		assertThat(err[0]).isGreaterThan(-0.01);
		assertThat(err[1]).isLessThan(0.01);
	}

	@ParameterizedTest(name = "{0}° postgis={1} forceJava={2}")
	@CsvSource({ "39.9, false, false", "39.9, true, true", "72.0, false, false", "72.0, true, true" })
	@DisplayName("Java 回落半径 / 最近邻：线中段穿过圆也命中，距离按地面米")
	@SuppressWarnings("unchecked")
	void radiusAndNearestFallback(double lat, boolean postgisUp, boolean forceJava) {
		GisSpatialQueryService svc = service(postgisUp);
		double lon = 20.0;
		// 东西向长线，两端顶点都在 5km 外，中段离查询点约 1.1 km（0.01° 纬度）
		GisLayer l = layer("{\"type\":\"LineString\",\"coordinates\":[[" + (lon - 0.5) + "," + (lat + 0.01) + "],["
			+ (lon + 0.5) + "," + (lat + 0.01) + "]]}");
		// 边按测地线（与 PostGIS geography 一致），高纬时中段向极点弯，真值沿测地线取点
		double truth = GeodesicTruth.minDistance(GeodesicTruth.GF.createLineString(new org.locationtech.jts.geom.Coordinate[] {
			new org.locationtech.jts.geom.Coordinate(lon - 0.5, lat + 0.01),
			new org.locationtech.jts.geom.Coordinate(lon + 0.5, lat + 0.01) }), lon, lat);
		Map<String, Object> r = svc.radius(l, lon, lat, 2000d, 10, forceJava);
		List<Map<String, Object>> feats = (List<Map<String, Object>>) ((Map<String, Object>) r.getOrDefault(
			"collection", r)).get("features");
		assertThat(feats).hasSize(1);
		double meters = ((Number) ((Map<String, Object>) feats.get(0).get("properties")).get("meters")).doubleValue();
		assertThat(meters / truth - 1).isBetween(-0.01, 0.01);
		Map<String, Object> n = svc.nearest(l, lon, lat, 1, forceJava);
		assertThat(n.get("engine")).isEqualTo(GisConstants.ENGINE_JAVA);
	}

	@ParameterizedTest(name = "{0}° postgis={1} forceJava={2}")
	@CsvSource({ "39.9, false, false", "72.0, true, true", "80.0, false, false" })
	@DisplayName("半径包络预筛不漏：贴着半径内侧（0.995·r）的点必须命中，外侧（1.005·r）不命中")
	@SuppressWarnings("unchecked")
	void radiusPrefilterDoesNotMiss(double lat, boolean postgisUp, boolean forceJava) {
		GisSpatialQueryService svc = service(postgisUp);
		double lon = 20.0;
		double r = 50_000d;
		// 正东方向：经度跨度最大、最容易被过紧的包络挡掉
		double dLonPerM = 1 / (GeodesicTruth.inverse(lon, lat, lon + 1, lat));
		double inLon = lon + r * 0.995 * dLonPerM;
		double outLon = lon + r * 1.005 * dLonPerM;
		GisLayer l = new GisLayer();
		l.setId(1L);
		l.setKind("vector");
		l.setDataJson("{\"type\":\"FeatureCollection\",\"features\":["
			+ "{\"type\":\"Feature\",\"id\":\"in\",\"properties\":{\"k\":\"in\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[" + inLon + "," + lat + "]}},"
			+ "{\"type\":\"Feature\",\"id\":\"out\",\"properties\":{\"k\":\"out\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[" + outLon + "," + lat + "]}}]}");
		Map<String, Object> res = svc.radius(l, lon, lat, r, 10, forceJava);
		List<Map<String, Object>> feats = (List<Map<String, Object>>) res.get("features");
		assertThat(feats).hasSize(1);
		assertThat(((Map<String, Object>) feats.get(0).get("properties")).get("k")).isEqualTo("in");
		Map<String, Object> near = svc.nearest(l, lon, lat, 1, forceJava);
		List<Map<String, Object>> nf = (List<Map<String, Object>>) near.get("features");
		assertThat(((Map<String, Object>) nf.get(0).get("properties")).get("k")).isEqualTo("in");
	}

	private GisLayer mixedLayer() {
		GisLayer l = new GisLayer();
		l.setId(7L);
		l.setKind("vector");
		l.setDataJson("{\"type\":\"FeatureCollection\",\"features\":["
			+ "{\"type\":\"Feature\",\"id\":\"ok\",\"properties\":{\"k\":\"ok\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[20.0,10.0]}},"
			+ "{\"type\":\"Feature\",\"id\":\"polar\",\"properties\":{\"k\":\"polar\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[20.0,85.5]}},"
			+ "{\"type\":\"Feature\",\"id\":\"pole\",\"properties\":{\"k\":\"pole\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[0.0,89.995]}},"
			+ "{\"type\":\"Feature\",\"id\":\"cross\",\"properties\":{\"k\":\"cross\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[179.0,10.0],[-179.0,10.0]]}}]}");
		return l;
	}

	@ParameterizedTest(name = "postgis={0} forceJava={1}")
	@CsvSource({ "false, false", "true, true" })
	@DisplayName("回落路径：极区、跨 180° 的要素照常缓冲；包住极点的跳过并记 skipped，不让整次查询失败")
	@SuppressWarnings("unchecked")
	void fallbackSkipsPolarAndAntimeridianInBuffer(boolean postgisUp, boolean forceJava) {
		GisSpatialQueryService svc = service(postgisUp);
		Map<String, Object> out = svc.buffer(mixedLayer(), 1000d, 10, forceJava);
		assertThat(out.get("skipped")).isEqualTo(1);
		List<Map<String, Object>> feats = (List<Map<String, Object>>) out.get("features");
		assertThat(feats).hasSize(3);
		assertThat(feats).extracting(f -> ((Map<String, Object>) f.get("properties")).get("k"))
			.containsExactly("ok", "polar", "cross");
		assertThat(((Map<String, Object>) feats.get(2).get("geometry")).get("type")).isEqualTo("MultiPolygon");
		assertThat(((Map<String, Object>) feats.get(0).get("properties")).get("k")).isEqualTo("ok");
	}

	@ParameterizedTest(name = "postgis={0} forceJava={1}")
	@CsvSource({ "false, false", "true, true" })
	@DisplayName("回落路径：极区要素按椭球测地距离计算，跨 180° 要素平移经度后求最近点，查询照常返回")
	@SuppressWarnings("unchecked")
	void fallbackHaversineForOutOfRangeFeatures(boolean postgisUp, boolean forceJava) {
		GisSpatialQueryService svc = service(postgisUp);
		// 查询点 (20, 84)：极区点 (20, 85.5) 正北 1.5°，测地距离 ≈ 167 km
		Map<String, Object> near = svc.nearest(mixedLayer(), 20.0, 84.0, 3, forceJava);
		List<Map<String, Object>> nf = (List<Map<String, Object>>) near.get("features");
		assertThat(nf).hasSize(3);
		Map<String, Object> first = (Map<String, Object>) nf.get(0).get("properties");
		assertThat(first.get("k")).isEqualTo("polar");
		double expect = GeodesicTruth.inverse(20, 84, 20, 85.5);
		assertThat(((Number) first.get("meters")).doubleValue() / expect - 1).isBetween(-0.001, 0.001);
		assertThat(near.get("skipped")).isEqualTo(0);
		Map<String, Object> r = svc.radius(mixedLayer(), 20.0, 84.0, 190_000d, 10, forceJava);
		List<Map<String, Object>> rf = (List<Map<String, Object>>) r.get("features");
		assertThat(rf).extracting(f -> ((Map<String, Object>) f.get("properties")).get("k")).containsExactly("polar");
	}

	@ParameterizedTest(name = "postgis={0} forceJava={1}")
	@CsvSource({ "false, false", "true, true" })
	@DisplayName("回落路径：查询点在极区也能查（不再报错）")
	@SuppressWarnings("unchecked")
	void fallbackPolarQueryPoint(boolean postgisUp, boolean forceJava) {
		GisSpatialQueryService svc = service(postgisUp);
		Map<String, Object> r = svc.radius(mixedLayer(), 0, 89.9, 50_000d, 10, forceJava);
		assertThat((List<Map<String, Object>>) r.get("features"))
			.extracting(f -> ((Map<String, Object>) f.get("properties")).get("k")).containsExactly("pole");
		Map<String, Object> n = svc.nearest(mixedLayer(), 0, 90, 1, forceJava);
		assertThat((List<Map<String, Object>>) n.get("features"))
			.extracting(f -> ((Map<String, Object>) f.get("properties")).get("k")).containsExactly("pole");
	}

	@ParameterizedTest(name = "postgis={0} forceJava={1}")
	@CsvSource({ "false, false", "true, true" })
	@DisplayName("回落路径：经度 179.9 的查询点能找到 -179.9 的要素（预筛包络按 ±180° 拆框）")
	@SuppressWarnings("unchecked")
	void antimeridianQueryFindsOtherSide(boolean postgisUp, boolean forceJava) {
		GisSpatialQueryService svc = service(postgisUp);
		GisLayer l = layer("{\"type\":\"Point\",\"coordinates\":[-179.9,0.0]}");
		Map<String, Object> r = svc.radius(l, 179.9, 0.0, 30_000d, 10, forceJava);
		List<Map<String, Object>> rf = (List<Map<String, Object>>) r.get("features");
		assertThat(rf).hasSize(1);
		double m = ((Number) ((Map<String, Object>) rf.get(0).get("properties")).get("meters")).doubleValue();
		assertThat(m / GeodesicTruth.inverse(179.9, 0, -179.9, 0) - 1).isBetween(-0.005, 0.005);
		Map<String, Object> rw = svc.radius(layer("{\"type\":\"Point\",\"coordinates\":[179.9,0.0]}"),
			-179.9, 0.0, 30_000d, 10, forceJava);
		assertThat((List<?>) rw.get("features")).hasSize(1);
		Map<String, Object> n = svc.nearest(l, 179.9, 0.0, 1, forceJava);
		assertThat((List<?>) n.get("features")).hasSize(1);
	}

	@org.junit.jupiter.api.Test
	@DisplayName("跳过日志按「操作 + 图层」限频：同一分钟内第二次不再 WARN，但 skipped 照常返回")
	void skipWarnIsRateLimited() {
		GisSpatialQueryService svc = service(false);
		GisLayer l = mixedLayer();
		l.setId(99L);
		ch.qos.logback.classic.Logger logger =
			(ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GisSpatialQueryService.class);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> app =
			new ch.qos.logback.core.read.ListAppender<>();
		app.start();
		logger.addAppender(app);
		try {
			assertThat(svc.buffer(l, 1000d, 10, false).get("skipped")).isEqualTo(1);
			assertThat(svc.buffer(l, 1000d, 10, false).get("skipped")).isEqualTo(1);
		} finally {
			logger.detachAppender(app);
		}
		List<ch.qos.logback.classic.spi.ILoggingEvent> warns = app.list.stream()
			.filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN).toList();
		assertThat(warns).hasSize(1);
		assertThat(warns.get(0).getFormattedMessage()).contains("layerId=99", "数量=1", "pole");
	}

	@ParameterizedTest(name = "postgis={0} forceJava={1}")
	@CsvSource({ "false, false", "true, true" })
	@DisplayName("回落路径：跨 180° 的线从 (0,0) 查不会误命中，从 (179.95,0.1) 查约 11.1 km")
	@SuppressWarnings("unchecked")
	void antimeridianFeatureNoFalseHit(boolean postgisUp, boolean forceJava) {
		GisSpatialQueryService svc = service(postgisUp);
		GisLayer l = layer("{\"type\":\"LineString\",\"coordinates\":[[179.9,0.0],[-179.9,0.0]]}");
		assertThat((List<?>) svc.radius(l, 0, 0, 200_000d, 10, forceJava).get("features")).isEmpty();
		Map<String, Object> r = svc.radius(l, 179.95, 0.1, 20_000d, 10, forceJava);
		List<Map<String, Object>> rf = (List<Map<String, Object>>) r.get("features");
		assertThat(rf).hasSize(1);
		double m = ((Number) ((Map<String, Object>) rf.get(0).get("properties")).get("meters")).doubleValue();
		assertThat(m / GeodesicTruth.inverse(179.95, 0.1, 179.95, 0) - 1).isBetween(-0.01, 0.01);
		assertThat(r.get("skipped")).isEqualTo(0);
	}

	@Test
	@DisplayName("视野查询：west > east（RFC 7946 §5.2）表示跨 180° 的范围，拆成两个框，两侧要素都能查到")
	@SuppressWarnings("unchecked")
	void bboxAcrossAntimeridian() {
		GisSpatialQueryService svc = service(false);
		GisLayer l = new GisLayer();
		l.setId(8L);
		l.setKind("vector");
		l.setDataJson("{\"type\":\"FeatureCollection\",\"features\":["
			+ "{\"type\":\"Feature\",\"id\":\"e\",\"properties\":{\"k\":\"e\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[179.5,0.0]}},"
			+ "{\"type\":\"Feature\",\"id\":\"w\",\"properties\":{\"k\":\"w\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[-179.5,0.0]}},"
			+ "{\"type\":\"Feature\",\"id\":\"o\",\"properties\":{\"k\":\"o\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[0.0,0.0]}}]}");
		Map<String, Object> r = svc.bbox(l, 179d, -1d, -179d, 1d, 10, true);
		assertThat((List<Map<String, Object>>) r.get("features"))
			.extracting(f -> ((Map<String, Object>) f.get("properties")).get("k")).containsExactlyInAnyOrder("e", "w");
		assertThat((List<?>) svc.bbox(l, -1d, -1d, 1d, 1d, 10, true).get("features")).hasSize(1);
		assertThatThrownBy(() -> svc.bbox(l, 10d, -1d, 10d, 1d, 10, true)).isInstanceOf(ServiceException.class);
	}

	@Test
	@SuppressWarnings("unchecked")
	@DisplayName("mugsun-boot#64：Java 路径对存量跨 180° 要素的 bbox / 半径")
	void storedAntimeridianJava() {
		GisSpatialQueryService svc = service(false);
		GisLayer l = new GisLayer();
		l.setId(9L);
		l.setKind("vector");
		l.setDataJson("{\"type\":\"FeatureCollection\",\"features\":["
			+ "{\"type\":\"Feature\",\"id\":\"l\",\"properties\":{\"k\":\"l\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[179.9,0],[-179.9,0]]}},"
			+ "{\"type\":\"Feature\",\"id\":\"p\",\"properties\":{\"k\":\"p\"},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[179,-1],[-179,-1],[-179,1],[179,1],[179,-1]]]}}]}");
		java.util.function.Function<Map<String, Object>, List<Object>> keys = r -> ((List<Map<String, Object>>) r.get("features"))
			.stream().map(f -> ((Map<String, Object>) f.get("properties")).get("k")).toList();
		assertThat(keys.apply(svc.bbox(l, 0d, -0.5d, 10d, 0.5d, 10, true))).isEmpty();
		assertThat(keys.apply(svc.bbox(l, 179.95d, -0.5d, -179.95d, 0.5d, 10, true))).containsExactlyInAnyOrder("l", "p");
		assertThat(keys.apply(svc.radius(l, 0d, 0d, 100_000d, 10, true))).isEmpty();
		assertThat(keys.apply(svc.radius(l, 179.95d, 0.1d, 20_000d, 10, true))).containsExactlyInAnyOrder("l", "p");
	}
}
