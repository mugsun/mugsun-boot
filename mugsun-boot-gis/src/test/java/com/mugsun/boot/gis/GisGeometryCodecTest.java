package com.mugsun.boot.gis;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * GeoJSON ↔ JTS 编解码：只认 WGS84 坐标数组，非法几何安静返回 null，不抛 500。
 */
class GisGeometryCodecTest {

	private GisGeometryCodec codec;

	@BeforeEach
	void setUp() {
		codec = new GisGeometryCodec();
	}

	private Map<String, Object> geom(String type, Object coordinates) {
		Map<String, Object> g = new LinkedHashMap<>();
		g.put("type", type);
		g.put("coordinates", coordinates);
		return g;
	}

	private Map<String, Object> feature(Map<String, Object> geometry) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("type", "Feature");
		f.put("properties", Map.of());
		f.put("geometry", geometry);
		return f;
	}

	@Test
	@DisplayName("Point 编解码往返")
	void pointRoundTrip() {
		Geometry g = codec.fromGeometry(geom("Point", List.of(116.4, 39.9)));
		assertThat(g).isInstanceOf(Point.class);
		assertThat(((Point) g).getX()).isCloseTo(116.4, within(1e-9));

		Map<String, Object> out = codec.toGeometry(g);
		assertThat(out.get("type")).isEqualTo("Point");
		@SuppressWarnings("unchecked")
		List<Number> xy = (List<Number>) out.get("coordinates");
		assertThat(xy.get(0).doubleValue()).isCloseTo(116.4, within(1e-9));
		assertThat(xy.get(1).doubleValue()).isCloseTo(39.9, within(1e-9));
	}

	@Test
	@DisplayName("闭合 Polygon 能建成；未闭合环由 JTS 自动补首尾点")
	void polygonRequiresClosedRing() {
		List<?> closed = List.of(
			List.of(0, 0), List.of(1, 0), List.of(1, 1), List.of(0, 1), List.of(0, 0)
		);
		Geometry poly = codec.fromGeometry(geom("Polygon", List.of(closed)));
		assertThat(poly).isInstanceOf(Polygon.class);
		assertThat(poly.isEmpty()).isFalse();

		// JTS LinearRing 构造会自动闭合，三点未闭合同样能建成三角形
		List<?> open = List.of(List.of(0, 0), List.of(1, 0), List.of(1, 1));
		Geometry autoClosed = codec.fromGeometry(geom("Polygon", List.of(open)));
		assertThat(autoClosed).isInstanceOf(Polygon.class);
		assertThat(autoClosed.isEmpty()).isFalse();
	}

	@Test
	@DisplayName("FeatureCollection 跳过空几何与非法项")
	void collectionSkipsBadFeatures() {
		Map<String, Object> collection = new LinkedHashMap<>();
		collection.put("type", "FeatureCollection");
		collection.put("features", List.of(
			feature(geom("Point", List.of(1, 2))),
			feature(geom("Unknown", List.of(1, 2))),
			Map.of("type", "Feature"),
			"not-a-feature"
		));

		List<Geometry> geoms = codec.fromCollection(collection);
		assertThat(geoms).hasSize(1);
		assertThat(geoms.get(0)).isInstanceOf(Point.class);
	}

	@Test
	@DisplayName("空/null 集合返回空列表，不抛异常")
	void nullCollectionSafe() {
		assertThat(codec.fromCollection(null)).isEmpty();
		assertThat(codec.fromCollection(Map.of())).isEmpty();
	}

	@Test
	@DisplayName("toFeature 带上 id 与 properties")
	void toFeatureKeepsIdAndProps() {
		Geometry g = codec.fromGeometry(geom("Point", List.of(1, 2)));
		Map<String, Object> feat = codec.toFeature(g, Map.of("name", "塔"), "42");

		assertThat(feat.get("type")).isEqualTo("Feature");
		assertThat(feat.get("id")).isEqualTo("42");
		assertThat(((Map<?, ?>) feat.get("properties")).get("name")).isEqualTo("塔");
		assertThat(((Map<?, ?>) feat.get("geometry")).get("type")).isEqualTo("Point");
	}

	@Test
	@DisplayName("Mercator 投影后面积单位是平方米")
	void mercatorAreaIsSquareMeters() {
		// 约 1°×1° 的方框（赤道附近）
		List<?> ring = List.of(
			List.of(0, 0), List.of(1, 0), List.of(1, 1), List.of(0, 1), List.of(0, 0)
		);
		Geometry poly = codec.fromGeometry(geom("Polygon", List.of(ring)));
		Geometry merc = codec.toMercator(poly);
		// 1° 经度约 111km，1° 纬度约 110km，面积量级 1e10 m²
		assertThat(merc.getArea()).isBetween(1e10, 2e10);
	}
}
