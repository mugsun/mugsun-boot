package com.mugsun.boot.gis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入站规范化的总量闸：要素数之外还限顶点总数，否则一段 WKT / CSV 就能塞进几十万个点，
 * 后面的拓扑校验、加密、分析都按点数付代价（BOOT-113）。分析结果不受这个上限约束。
 */
class GisFormatServiceTest {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final GisGeometryCodec codec = new GisGeometryCodec();
	private final GisFormatService format = new GisFormatService(objectMapper, new GisTextIngest(codec),
		new GisTopologyGuard(codec));

	/** count 条线，每条 vertices 个点，锯齿形让相邻点都不重复 */
	private static List<Map<String, Object>> lines(int count, int vertices) {
		List<Map<String, Object>> feats = new ArrayList<>();
		for (int f = 0; f < count; f++) {
			List<List<Double>> coords = new ArrayList<>(vertices);
			for (int i = 0; i < vertices; i++) {
				coords.add(List.of(100 + f * 2 + i * 1e-5, 30 + (i % 2) * 1e-5));
			}
			Map<String, Object> feat = new LinkedHashMap<>();
			feat.put("type", "Feature");
			feat.put("properties", Map.of());
			feat.put("geometry", Map.of("type", "LineString", "coordinates", coords));
			feats.add(feat);
		}
		return feats;
	}

	private Map<String, Object> normalize(List<Map<String, Object>> feats) {
		return format.normalize(objectMapper.valueToTree(Map.of("type", "FeatureCollection", "features", feats)));
	}

	@Test
	@DisplayName("入参合计正好 20 万个顶点放过")
	void acceptsVertexLimit() {
		assertThat(normalize(lines(2, GisConstants.VERTEX_MAX / 2)).get("count")).isEqualTo(2);
	}

	@Test
	@DisplayName("入参合计超过 20 万个顶点整批拒收，并报出这次的点数")
	void rejectsTooManyVertices() {
		assertThatThrownBy(() -> normalize(lines(2, GisConstants.VERTEX_MAX / 2 + 1)))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("200002");
	}

	@Test
	@DisplayName("WKT 原文每个点只占几个字节，同样按顶点总数拦")
	void rejectsDenseWkt() {
		StringBuilder wkt = new StringBuilder("LINESTRING (");
		for (int i = 0; i <= GisConstants.VERTEX_MAX; i++) {
			wkt.append(i == 0 ? "" : ", ").append(i % 2 == 0 ? "0 0" : "1 1");
		}
		wkt.append(')');
		assertThatThrownBy(() -> format.normalizeUnknown(wkt.toString()))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("200001");
	}

	@Test
	@DisplayName("分析结果不按入参上限拦：缓冲会把一个点变成上百个顶点的圆")
	void resultsAreNotVertexCapped() {
		assertThat(format.normalizeResult(lines(2, GisConstants.VERTEX_MAX / 2 + 1)).get("count")).isEqualTo(2);
	}
}
