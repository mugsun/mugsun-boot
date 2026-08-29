package com.mugsun.boot.gis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.gis.entity.GisLayer;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 空间查询的 Java 回落路径与参数校验。
 *
 * <p>这里刻意把 {@link GisSpatialSupport#available()} 打成 false，只测回落路径：
 * 下沉路径依赖真实 PostGIS，放在集成测试里跑（{@code GisApiTest}）。回落路径是没有 PostGIS 的库
 * （达梦 / 金仓）唯一的执行路径，反而更需要算法级用例守着。
 */
class GisSpatialQueryServiceTest {

	private GisSpatialQueryService service;

	/** 天安门附近四个点：中心、约 500m、约 3km、以及远在上海的一个 */
	private static final String FEATURES = """
		{"type":"FeatureCollection","features":[
		 {"type":"Feature","properties":{"name":"中心"},"geometry":{"type":"Point","coordinates":[116.3975,39.9087]}},
		 {"type":"Feature","properties":{"name":"近点"},"geometry":{"type":"Point","coordinates":[116.4033,39.9087]}},
		 {"type":"Feature","properties":{"name":"中距"},"geometry":{"type":"Point","coordinates":[116.4326,39.9087]}},
		 {"type":"Feature","properties":{"name":"上海"},"geometry":{"type":"Point","coordinates":[121.4737,31.2304]}}
		]}
		""";

	@BeforeEach
	void setUp() {
		GisSpatialSupport support = mock(GisSpatialSupport.class);
		when(support.available()).thenReturn(false);
		GisFeatureStore store = mock(GisFeatureStore.class);
		service = new GisSpatialQueryService(support, store, new GisGeometryCodec(), new ObjectMapper());
	}

	private static GisLayer layer(String kind, String data) {
		GisLayer row = new GisLayer();
		row.setId(1L);
		row.setKind(kind);
		row.setDataJson(data);
		row.setFeatureCount(4);
		return row;
	}

	private static GisLayer vector() {
		return layer(GisConstants.KIND_VECTOR, FEATURES);
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> featuresOf(Map<String, Object> result) {
		return (List<Map<String, Object>>) result.get("features");
	}

	private static String nameOf(Map<String, Object> feature) {
		return String.valueOf(((Map<?, ?>) feature.get("properties")).get("name"));
	}

	@Test
	@DisplayName("bbox 只回范围内的要素，并标明走了回落引擎")
	void bboxFiltersByEnvelope() {
		Map<String, Object> out = service.bbox(vector(), 116.39, 39.90, 116.41, 39.92, 0, false);
		assertThat(out.get("engine")).isEqualTo(GisConstants.ENGINE_JAVA);
		assertThat(featuresOf(out)).extracting(GisSpatialQueryServiceTest::nameOf)
			.containsExactly("中心", "近点");
	}

	@Test
	@DisplayName("半径查询按米筛选，500m 只命中中心与近点")
	void radiusFiltersByMeters() {
		Map<String, Object> out = service.radius(vector(), 116.3975, 39.9087, 600, 0, false);
		assertThat(featuresOf(out)).extracting(GisSpatialQueryServiceTest::nameOf)
			.containsExactly("中心", "近点");
	}

	@Test
	@DisplayName("半径距离用球面公式，与真实距离的偏差在 1% 内")
	void radiusReportsRealMeters() {
		Map<String, Object> out = service.radius(vector(), 116.3975, 39.9087, 5000, 0, false);
		Map<String, Object> near = featuresOf(out).get(1);
		double meters = ((Number) ((Map<?, ?>) near.get("properties")).get("meters")).doubleValue();
		// 116.3975→116.4033 在北纬 39.9 约合 496m
		assertThat(meters).isBetween(490d, 502d);
	}

	@Test
	@DisplayName("最近邻按距离升序，最近的排最前")
	void nearestSortsByDistance() {
		Map<String, Object> out = service.nearest(vector(), 116.3975, 39.9087, 3, false);
		assertThat(featuresOf(out)).extracting(GisSpatialQueryServiceTest::nameOf)
			.containsExactly("中心", "近点", "中距");
	}

	@Test
	@DisplayName("相交查询接受 geometry 也接受整个 Feature")
	void intersectsAcceptsGeometryOrFeature() {
		Map<String, Object> geom = Map.of("type", "Polygon", "coordinates",
			List.of(List.of(List.of(116.39, 39.90), List.of(116.41, 39.90),
				List.of(116.41, 39.92), List.of(116.39, 39.92), List.of(116.39, 39.90))));
		Map<String, Object> byGeometry = service.intersects(vector(), geom, 0, false);
		Map<String, Object> byFeature = service.intersects(vector(),
			Map.of("type", "Feature", "geometry", geom), 0, false);
		assertThat(featuresOf(byGeometry)).hasSize(2);
		assertThat(featuresOf(byFeature)).hasSize(2);
	}

	@Test
	@DisplayName("limit 生效并标记结果被截断")
	void limitTruncates() {
		Map<String, Object> out = service.bbox(vector(), 100, 20, 130, 50, 2, false);
		assertThat(featuresOf(out)).hasSize(2);
		assertThat(out.get("truncated")).isEqualTo(true);
		assertThat(out.get("count")).isEqualTo(2);
	}

	@Test
	@DisplayName("栅格与三维切片图层没有几何，直接拒收")
	void rejectsNonVectorLayers() {
		for (String kind : List.of(GisConstants.KIND_XYZ, GisConstants.KIND_WMS, GisConstants.KIND_3DTILES)) {
			assertThatThrownBy(() -> service.bbox(layer(kind, "{}"), 1, 1, 2, 2, 0, false))
				.isInstanceOf(ServiceException.class)
				.hasMessage(GisConstants.MSG_SPATIAL_KIND);
		}
	}

	@Test
	@DisplayName("非法范围 / 半径 / 几何都给明确提示")
	void rejectsBadArguments() {
		assertThatThrownBy(() -> service.bbox(vector(), 116.41, 39.9, 116.39, 39.92, 0, false))
			.hasMessage(GisConstants.MSG_SPATIAL_BBOX);
		assertThatThrownBy(() -> service.bbox(vector(), -200, 39.9, 116.39, 39.92, 0, false))
			.hasMessage(GisConstants.MSG_SPATIAL_BBOX);
		assertThatThrownBy(() -> service.radius(vector(), 116.4, 39.9, 0, 0, false))
			.hasMessage(GisConstants.MSG_SPATIAL_RADIUS);
		assertThatThrownBy(() -> service.radius(vector(), 116.4, 39.9, GisConstants.RADIUS_MAX_M + 1, 0, false))
			.hasMessage(GisConstants.MSG_SPATIAL_RADIUS);
		assertThatThrownBy(() -> service.nearest(vector(), 999, 39.9, 5, false))
			.hasMessage(GisConstants.MSG_SPATIAL_POINT);
		assertThatThrownBy(() -> service.intersects(vector(), Map.of("type", "Polygon"), 0, false))
			.hasMessage(GisConstants.MSG_SPATIAL_GEOM);
	}

	@Test
	@DisplayName("坏 JSON 图层报可读错误而不是抛栈")
	void rejectsBrokenLayerJson() {
		assertThatThrownBy(() -> service.bbox(layer(GisConstants.KIND_VECTOR, "{not json"), 1, 1, 2, 2, 0, false))
			.isInstanceOf(ServiceException.class)
			.hasMessage(GisConstants.MSG_LAYER_INVALID);
	}

	@Test
	@DisplayName("空图层不报错，回空集合")
	void emptyLayerReturnsEmpty() {
		Map<String, Object> out = service.bbox(
			layer(GisConstants.KIND_VECTOR, "{\"type\":\"FeatureCollection\",\"features\":[]}"),
			1, 1, 2, 2, 0, false);
		assertThat(featuresOf(out)).isEmpty();
		assertThat(out.get("count")).isEqualTo(0);
	}

	@Test
	@DisplayName("要素几何缺失或非法时跳过，不影响其余要素")
	void skipsBrokenFeatures() {
		String data = """
			{"type":"FeatureCollection","features":[
			 {"type":"Feature","properties":{"name":"没几何"}},
			 {"type":"Feature","properties":{"name":"空几何"},"geometry":{}},
			 {"type":"Feature","properties":{"name":"好的"},"geometry":{"type":"Point","coordinates":[116.3975,39.9087]}}
			]}
			""";
		Map<String, Object> out = service.bbox(layer(GisConstants.KIND_VECTOR, data),
			116.39, 39.90, 116.41, 39.92, 0, false);
		assertThat(featuresOf(out)).extracting(GisSpatialQueryServiceTest::nameOf).containsExactly("好的");
	}

	@Test
	@DisplayName("indexable 只认矢量与热力")
	void indexableKinds() {
		assertThat(GisFeatureStore.indexable(GisConstants.KIND_VECTOR)).isTrue();
		assertThat(GisFeatureStore.indexable(GisConstants.KIND_HEATMAP)).isTrue();
		assertThat(GisFeatureStore.indexable(GisConstants.KIND_XYZ)).isFalse();
		assertThat(GisFeatureStore.indexable(GisConstants.KIND_3DTILES)).isFalse();
		assertThat(GisFeatureStore.indexable(null)).isFalse();
	}
}
