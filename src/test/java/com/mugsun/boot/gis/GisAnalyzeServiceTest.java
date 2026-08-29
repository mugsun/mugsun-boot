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
 * 空间分析内核：缓冲/相交/距离/面积走 JTS（米制 3857），出入 WGS84。
 * 不启 Spring 容器，真实 codec + format，mapper 用 mock（本用例不碰图层落库）。
 */
class GisAnalyzeServiceTest {

	private GisAnalyzeService service;

	@BeforeEach
	void setUp() {
		GisGeometryCodec codec = new GisGeometryCodec();
		GisFormatService format = new GisFormatService(new ObjectMapper(), new GisTextIngest(codec),
			new GisTopologyGuard(codec));
		service = new GisAnalyzeService(format, codec, mock(GisLayerMapper.class));
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
	@DisplayName("两点距离（米制）在合理量级：约 1° 经度 ≈ 111km")
	void distanceNearEquator() {
		Map<String, Object> req = body("distance", collection(point(0, 0)));
		req.put("other", collection(point(1, 0)));
		double meters = ((Number) ((Map<?, ?>) service.analyze(req).get("metrics")).get("distanceMeters")).doubleValue();
		assertThat(meters).isCloseTo(111319d, within(50d));
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
}
