package com.mugsun.boot.gis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.gis.mapper.GisLayerMapper;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;

/**
 * 空间分析内核：缓冲/相交/距离/面积走 JTS，米制走 GisGeodesic，出入 WGS84。
 * 不启 Spring 容器，真实 codec + format，mapper 用 mock（本用例不碰图层落库）。
 */
class GisAnalyzeServiceTest {

	private GisAnalyzeService service;

	@BeforeEach
	void setUp() {
		GisGeometryCodec codec = new GisGeometryCodec();
		GisFormatService format = new GisFormatService(new ObjectMapper(), new GisTextIngest(codec),
			new GisTopologyGuard(codec));
		service = new GisAnalyzeService(format, codec, mock(GisLayerMapper.class),
			mock(GisSpatialQueryService.class));
	}

	private Map<String, Object> collection(Object... featureGeoms) {
		List<Map<String, Object>> feats = new java.util.ArrayList<>();
		for (Object g : featureGeoms) {
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("type", "Feature");
			f.put("properties", Map.of());
			f.put("geometry", g);
			feats.add(f);
		}
		Map<String, Object> c = new LinkedHashMap<>();
		c.put("type", "FeatureCollection");
		c.put("features", feats);
		return c;
	}

	private Map<String, Object> point(double lon, double lat) {
		return Map.of("type", "Point", "coordinates", List.of(lon, lat));
	}

	private Map<String, Object> square(double minLon, double minLat, double maxLon, double maxLat) {
		List<?> ring = List.of(
			List.of(minLon, minLat), List.of(maxLon, minLat),
			List.of(maxLon, maxLat), List.of(minLon, maxLat), List.of(minLon, minLat)
		);
		return Map.of("type", "Polygon", "coordinates", List.of(ring));
	}

	private Map<String, Object> body(String op, Map<String, Object> payload) {
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("op", op);
		b.put("payload", payload);
		return b;
	}

	@Test
	@DisplayName("非法 op / 空载荷抛业务异常")
	void rejectsBadInput() {
		assertThatThrownBy(() -> service.analyze(null)).isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> service.analyze(Map.of("op", "nope")))
			.isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> service.analyze(body("buffer", collection())))
			.isInstanceOf(ServiceException.class);
	}

	@Test
	@DisplayName("点缓冲 1000 米产出多边形，metrics 带回 bufferMeters")
	void bufferPointProducesPolygon() {
		Map<String, Object> req = body("buffer", collection(point(116.4, 39.9)));
		req.put("distance", 1000);
		Map<String, Object> out = service.analyze(req);

		assertThat(out.get("op")).isEqualTo("buffer");
		@SuppressWarnings("unchecked")
		Map<String, Object> metrics = (Map<String, Object>) out.get("metrics");
		assertThat(metrics.get("bufferMeters")).isEqualTo(1000.0d);

		@SuppressWarnings("unchecked")
		Map<String, Object> col = (Map<String, Object>) out.get("collection");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> feats = (List<Map<String, Object>>) col.get("features");
		assertThat(feats).isNotEmpty();
		assertThat(((Map<?, ?>) feats.get(0).get("geometry")).get("type")).isEqualTo("Polygon");
	}

	@Test
	@DisplayName("缓冲距离越界被拒绝")
	void bufferDistanceOutOfRange() {
		Map<String, Object> req = body("buffer", collection(point(116.4, 39.9)));
		req.put("distance", 0);
		assertThatThrownBy(() -> service.analyze(req)).isInstanceOf(ServiceException.class);

		req.put("distance", GisConstants.BUFFER_MAX_M + 1);
		assertThatThrownBy(() -> service.analyze(req)).isInstanceOf(ServiceException.class);
	}

	@Test
	@DisplayName("两块重叠矩形 intersects=true，分离矩形为 false")
	void intersectsDetectsOverlap() {
		Map<String, Object> a = collection(square(0, 0, 2, 2));
		Map<String, Object> overlap = collection(square(1, 1, 3, 3));
		Map<String, Object> far = collection(square(10, 10, 11, 11));

		Map<String, Object> hit = body("intersects", a);
		hit.put("other", overlap);
		assertThat(((Map<?, ?>) service.analyze(hit).get("metrics")).get("intersects")).isEqualTo(true);

		Map<String, Object> miss = body("intersects", a);
		miss.put("other", far);
		assertThat(((Map<?, ?>) service.analyze(miss).get("metrics")).get("intersects")).isEqualTo(false);
	}

	@Test
	@DisplayName("包含关系：大方框 contains 小方框")
	void containsNestedSquare() {
		Map<String, Object> req = body("contains", collection(square(0, 0, 10, 10)));
		req.put("other", collection(square(2, 2, 3, 3)));
		assertThat(((Map<?, ?>) service.analyze(req).get("metrics")).get("contains")).isEqualTo(true);
	}

	@Test
	@DisplayName("两点距离按地面米：赤道 1° 经度与 70°N 1° 经度都对上 WGS84 测地距离（误差 < 0.5%）")
	void distanceNearEquator() {
		for (double lat : new double[] { 0d, 70d }) {
			Map<String, Object> req = body("distance", collection(point(0, lat)));
			req.put("other", collection(point(1, lat)));
			double meters = ((Number) ((Map<?, ?>) service.analyze(req).get("metrics")).get("distanceMeters"))
				.doubleValue();
			double truth = GeodesicTruth.inverse(0, lat, 1, lat);
			assertThat(meters / truth - 1).isBetween(-0.005, 0.005);
		}
	}

	@Test
	@DisplayName("面积运算返回正的 areaSqMeters")
	void areaIsPositive() {
		Map<String, Object> out = service.analyze(body("area", collection(square(116, 39, 116.01, 39.01))));
		double area = ((Number) ((Map<?, ?>) out.get("metrics")).get("areaSqMeters")).doubleValue();
		assertThat(area).isPositive();
	}

	@Test
	@DisplayName("bbox 回填 min/max 经纬度")
	void bboxMetrics() {
		Map<String, Object> out = service.analyze(body("bbox", collection(square(116, 39, 117, 40))));
		Map<?, ?> metrics = (Map<?, ?>) out.get("metrics");
		assertThat(((Number) metrics.get("minLon")).doubleValue()).isCloseTo(116d, within(1e-6));
		assertThat(((Number) metrics.get("maxLat")).doubleValue()).isCloseTo(40d, within(1e-6));
	}

	@Test
	@DisplayName("二元运算缺 other 抛业务异常")
	void binaryRequiresOther() {
		assertThatThrownBy(() -> service.analyze(body("intersects", collection(point(1, 1)))))
			.isInstanceOf(ServiceException.class);
	}

	private GisAnalyzeService withLayer(GisSpatialQueryService spatial, String geometryJson) {
		GisGeometryCodec codec = new GisGeometryCodec();
		GisFormatService format = new GisFormatService(new ObjectMapper(), new GisTextIngest(codec),
			new GisTopologyGuard(codec));
		GisLayerMapper mapper = mock(GisLayerMapper.class);
		com.mugsun.boot.gis.entity.GisLayer l = new com.mugsun.boot.gis.entity.GisLayer();
		l.setId(5L);
		l.setKind("vector");
		l.setDataJson("{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"id\":\"f\","
			+ "\"properties\":{},\"geometry\":" + geometryJson + "}]}");
		org.mockito.Mockito.when(mapper.selectOneById(5L)).thenReturn(l);
		return new GisAnalyzeService(format, codec, mapper, spatial);
	}

	@Test
	@DisplayName("engine=java：不转交图层查询（不走 tryOffload），整份入参在内存里分析")
	void engineJavaSkipsOffload() {
		GisSpatialQueryService spatial = mock(GisSpatialQueryService.class);
		GisAnalyzeService svc = withLayer(spatial, "{\"type\":\"Point\",\"coordinates\":[116.4,39.9]}");
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("op", "buffer");
		body.put("layerId", 5L);
		body.put("distance", 500);
		body.put("engine", "java");
		Map<String, Object> out = svc.analyze(body);
		assertThat(out.get("engine")).isEqualTo("java");
		org.mockito.Mockito.verifyNoInteractions(spatial);
		// 不带 engine 时照旧转交
		body.remove("engine");
		org.mockito.Mockito.when(spatial.buffer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyDouble(),
			org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean()))
			.thenReturn(new LinkedHashMap<>(Map.of("features", List.of(), "engine", "java")));
		svc.analyze(body);
		org.mockito.Mockito.verify(spatial).buffer(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(500d),
			org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq(false));
	}

	@Test
	@DisplayName("分析单个几何（engine=java 或直接传入）：缓冲包住极点 / 单个部件宽于 180° 报错")
	void analyzeInputOutOfRangeThrows() {
		GisAnalyzeService svc = withLayer(mock(GisSpatialQueryService.class),
			"{\"type\":\"Point\",\"coordinates\":[0,89.999]}");
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("op", "buffer");
		body.put("layerId", 5L);
		body.put("distance", 500);
		body.put("engine", "java");
		assertThatThrownBy(() -> svc.analyze(body)).isInstanceOf(ServiceException.class).hasMessageContaining("极点");
		Map<String, Object> direct = new LinkedHashMap<>();
		direct.put("op", "buffer");
		direct.put("distance", 500);
		direct.put("payload", collection(Map.of("type", "LineString", "coordinates",
			List.of(List.of(-100, 0), List.of(0, 0), List.of(100, 0)))));
		assertThatThrownBy(() -> service.analyze(direct)).isInstanceOf(ServiceException.class).hasMessageContaining("180°");
	}

	@Test
	@DisplayName("转交图层查询时 wrapSpatial 把 skipped 带回，跨 180° 的命中要素不让汇总抛异常")
	@SuppressWarnings("unchecked")
	void wrapSpatialCarriesSkipped() {
		GisSpatialQueryService spatial = mock(GisSpatialQueryService.class);
		GisAnalyzeService svc = withLayer(spatial, "{\"type\":\"Point\",\"coordinates\":[116.4,39.9]}");
		Map<String, Object> polarFeat = new LinkedHashMap<>();
		polarFeat.put("type", "Feature");
		polarFeat.put("properties", new LinkedHashMap<>());
		polarFeat.put("geometry", Map.of("type", "Polygon", "coordinates",
			List.of(List.of(List.of(-100, 0), List.of(0, 0), List.of(100, 0), List.of(100, 1), List.of(-100, 0)))));
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("features", new java.util.ArrayList<>(List.of(polarFeat)));
		resp.put("engine", "java");
		resp.put("count", 1);
		resp.put("skipped", 3);
		org.mockito.Mockito.when(spatial.contains(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(resp);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("op", "contains");
		body.put("layerId", 5L);
		body.put("other", Map.of("type", "Point", "coordinates", List.of(0.5, 86.5)));
		Map<String, Object> out = svc.analyze(body);
		assertThat(out.get("skipped")).isEqualTo(3);
		assertThat(((Map<String, Object>) out.get("metrics")).get("metricsSkipped")).isEqualTo(1);
	}
}
