package com.mugsun.boot.gis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.gis.entity.GisLayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.locationtech.jts.geom.Geometry;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
		double truth = GeodesicTruth.vincenty(lon, lat, lon, lat + 0.01);
		Map<String, Object> r = svc.radius(l, lon, lat, 2000d, 10, forceJava);
		List<Map<String, Object>> feats = (List<Map<String, Object>>) ((Map<String, Object>) r.getOrDefault(
			"collection", r)).get("features");
		assertThat(feats).hasSize(1);
		double meters = ((Number) ((Map<String, Object>) feats.get(0).get("properties")).get("meters")).doubleValue();
		assertThat(meters / truth - 1).isBetween(-0.01, 0.01);
		Map<String, Object> n = svc.nearest(l, lon, lat, 1, forceJava);
		assertThat(n.get("engine")).isEqualTo(GisConstants.ENGINE_JAVA);
	}
}
