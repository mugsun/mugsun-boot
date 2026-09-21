package com.mugsun.boot.gis;

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
 * 拓扑校验：哪些脏几何必须拒收、哪些应当修复后放过。
 *
 * <p>用例按「拒收」与「修复」两档组织，与 {@link GisTopologyGuard} 的处置界线一一对应。
 * 这里不碰数据库，纯算法级，因为拒收判定是入站的最后一道闸，回归成本必须足够低。
 */
class GisTopologyGuardTest {

	private final GisTopologyGuard guard = new GisTopologyGuard(new GisGeometryCodec());

	private static Map<String, Object> feature(String type, Object coords) {
		Map<String, Object> geom = new LinkedHashMap<>();
		geom.put("type", type);
		geom.put("coordinates", coords);
		Map<String, Object> feat = new LinkedHashMap<>();
		feat.put("type", "Feature");
		feat.put("properties", new LinkedHashMap<>(Map.of("name", "用例")));
		feat.put("geometry", geom);
		return feat;
	}

	private static List<Double> pt(double lon, double lat) {
		return List.of(lon, lat);
	}

	private GisTopologyGuard.Result inspect(Map<String, Object> feature) {
		List<Map<String, Object>> in = new ArrayList<>();
		in.add(feature);
		return guard.inspect(in);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> geometryOf(GisTopologyGuard.Result result) {
		return (Map<String, Object>) result.features().get(0).get("geometry");
	}

	// ==================== 放过 ====================

	@Test
	@DisplayName("干净几何原样放过，不产生警告也不改写")
	void cleanGeometryUntouched() {
		Map<String, Object> feat = feature("Point", pt(116.3975, 39.9087));
		GisTopologyGuard.Result out = inspect(feat);
		assertThat(out.warnings()).isEmpty();
		assertThat(out.features().get(0)).isSameAs(feat);
	}

	@Test
	@DisplayName("闭合且不自相交的面放过")
	void validPolygonUntouched() {
		Map<String, Object> feat = feature("Polygon", List.of(List.of(
			pt(116.39, 39.90), pt(116.41, 39.90), pt(116.41, 39.92), pt(116.39, 39.92), pt(116.39, 39.90))));
		GisTopologyGuard.Result out = inspect(feat);
		assertThat(out.warnings()).isEmpty();
	}

	@Test
	@DisplayName("边界值 ±180 / ±90 是合法坐标，不能误拒")
	void extremaAreValid() {
		for (List<Double> p : List.of(pt(180, 90), pt(-180, -90), pt(0, 0))) {
			assertThat(inspect(feature("Point", p)).warnings()).isEmpty();
		}
	}

	// ==================== 硬拒收 ====================

	@Test
	@DisplayName("坐标越界整批拒收，并指出第几个要素与越界值")
	void rejectsOutOfRange() {
		List<Map<String, Object>> batch = new ArrayList<>();
		batch.add(feature("Point", pt(116.39, 39.90)));
		batch.add(feature("Point", pt(187.5, 39.90)));
		assertThatThrownBy(() -> guard.inspect(batch))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("第 2 个要素")
			.hasMessageContaining("187.5");
	}

	@Test
	@DisplayName("纬度越界同样拒收（把墨卡托米当经纬度传的典型症状）")
	void rejectsMercatorMetersMistakenAsLonLat() {
		assertThatThrownBy(() -> inspect(feature("Point", pt(12958065.0, 4852834.0))))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("坐标越界");
	}

	@Test
	@DisplayName("NaN / Infinity 拒收，且报「不是有效数字」而不是越界")
	void rejectsNonFinite() {
		for (double bad : new double[] { Double.NaN, Double.POSITIVE_INFINITY }) {
			assertThatThrownBy(() -> inspect(feature("Point", List.of(bad, 39.9))))
				.isInstanceOf(ServiceException.class)
				.hasMessageContaining("不是有效数字");
		}
	}

	@Test
	@DisplayName("字符串型非数坐标（\"NaN\"）同样拒收，不误报成「无法修复」")
	void rejectsStringNaN() {
		assertThatThrownBy(() -> inspect(feature("Point", List.of("NaN", "39.9"))))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("不是有效数字");
	}

	@Test
	@DisplayName("线只有一个不同的点属退化，拒收")
	void rejectsDegenerateLine() {
		assertThatThrownBy(() -> inspect(feature("LineString",
			List.of(pt(116.39, 39.90), pt(116.39, 39.90)))))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("线至少需要两个不同的点");
	}

	@Test
	@DisplayName("面的环不足三个不同的点属退化，拒收")
	void rejectsDegeneratePolygon() {
		assertThatThrownBy(() -> inspect(feature("Polygon", List.of(List.of(
			pt(116.39, 39.90), pt(116.41, 39.90), pt(116.39, 39.90))))))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("环至少需要三个不同的点");
	}

	@Test
	@DisplayName("坐标对不完整拒收")
	void rejectsIncompleteCoordinatePair() {
		assertThatThrownBy(() -> inspect(feature("Point", List.of(116.39))))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("坐标对不完整");
	}

	@Test
	@DisplayName("不认识的几何类型拒收，并把类型名带回去")
	void rejectsUnknownType() {
		assertThatThrownBy(() -> inspect(feature("Circle", pt(116.39, 39.90))))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("Circle");
	}

	// ==================== 修复并回报 ====================

	@Test
	@DisplayName("环未闭合：自动闭合并回报，不拒收")
	void closesUnclosedRing() {
		GisTopologyGuard.Result out = inspect(feature("Polygon", List.of(List.of(
			pt(116.39, 39.90), pt(116.41, 39.90), pt(116.41, 39.92), pt(116.39, 39.92)))));
		assertThat(out.warnings()).singleElement().asString().contains("未闭合，已自动闭合");
		List<?> ring = (List<?>) ((List<?>) geometryOf(out).get("coordinates")).get(0);
		assertThat(ring).hasSize(5);
		assertThat(ring.get(0)).isEqualTo(ring.get(4));
	}

	@Test
	@DisplayName("自相交的面：按 OGC 规则修复后放过，并回报改动")
	void fixesSelfIntersection() {
		// 蝴蝶结（bow-tie）：对角相连，中间交叉一次
		GisTopologyGuard.Result out = inspect(feature("Polygon", List.of(List.of(
			pt(116.39, 39.90), pt(116.41, 39.92), pt(116.41, 39.90), pt(116.39, 39.92),
			pt(116.39, 39.90)))));
		assertThat(out.warnings()).singleElement().asString().contains("自相交");
		assertThat(String.valueOf(geometryOf(out).get("type"))).contains("Polygon");
	}

	@Test
	@DisplayName("一批里多个问题要素，警告逐条带序号回报")
	void reportsPerFeatureWarnings() {
		List<Map<String, Object>> batch = new ArrayList<>();
		batch.add(feature("Point", pt(116.39, 39.90)));
		batch.add(feature("Polygon", List.of(List.of(
			pt(116.39, 39.90), pt(116.41, 39.90), pt(116.41, 39.92), pt(116.39, 39.92)))));
		batch.add(feature("Polygon", List.of(List.of(
			pt(117.39, 39.90), pt(117.41, 39.90), pt(117.41, 39.92), pt(117.39, 39.92)))));
		GisTopologyGuard.Result out = guard.inspect(batch);
		assertThat(out.warnings()).hasSize(2);
		assertThat(out.warnings().get(0)).contains("第 2 个要素");
		assertThat(out.warnings().get(1)).contains("第 3 个要素");
	}

	@Test
	@DisplayName("没有 geometry 的要素跳过校验，交由上游按空要素处理")
	void skipsFeatureWithoutGeometry() {
		Map<String, Object> feat = new LinkedHashMap<>();
		feat.put("type", "Feature");
		feat.put("properties", new LinkedHashMap<>());
		GisTopologyGuard.Result out = inspect(feat);
		assertThat(out.warnings()).isEmpty();
		assertThat(out.features()).hasSize(1);
	}
}
