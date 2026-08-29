package com.mugsun.boot.gis;

import com.mugsun.core.tool.exception.ServiceException;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.util.GeometryFixer;
import org.locationtech.jts.operation.valid.IsValidOp;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 入站几何的拓扑校验与脏数据处置。所有图层入站都从这里过一遍，脏几何不许落库。
 *
 * <p>两档处置，界线是「能不能在不猜用户意图的前提下修好」：
 * <ul>
 *   <li><b>硬拒收</b>：坐标越界、非数（NaN / Infinity）、几何退化（线只剩一个点、环不足三点）、
 *       类型不认识。这些要么是坐标系搞错（把墨卡托米当经纬度传），要么是数据本身残缺，
 *       猜不出正确值，只能整批拒收并指出第几个要素——比默默丢掉几条要素更容易排查。</li>
 *   <li><b>修复并回报</b>：环未闭合、多边形自相交。前者补上首点即可；后者交给 JTS
 *       {@link GeometryFixer}（等价于 PostGIS 的 {@code ST_MakeValid}）。修完把警告随
 *       响应带回前端，不能悄悄改用户的数据。</li>
 * </ul>
 *
 * <p>为什么不把自相交也拒收：手绘标绘、GPS 轨迹转面、上游简化算法都会自然产出自相交环，
 * 一律拒收会让正常业务卡住；而修复结果是确定的（按 OGC 有效性规则拆成多部件），可以自动做。
 */
@Component
public class GisTopologyGuard {

	/** 一批要素的校验结论：修复后的要素 + 如实回报的警告 */
	public record Result(List<Map<String, Object>> features, List<String> warnings) {
	}

	private final GisGeometryCodec codec;

	public GisTopologyGuard(GisGeometryCodec codec) {
		this.codec = codec;
	}

	/**
	 * 逐要素校验。硬错误直接抛 {@link ServiceException}（整批拒收），
	 * 可修复问题就地改写要素的 geometry 并累计警告。
	 */
	public Result inspect(List<Map<String, Object>> features) {
		List<String> warnings = new ArrayList<>();
		List<Map<String, Object>> out = new ArrayList<>(features.size());
		for (int i = 0; i < features.size(); i++) {
			out.add(inspectOne(features.get(i), i + 1, warnings));
		}
		return new Result(out, warnings);
	}

	private Map<String, Object> inspectOne(Map<String, Object> feature, int no, List<String> warnings) {
		if (!(feature.get("geometry") instanceof Map<?, ?> geomMap)) {
			return feature;
		}
		String type = String.valueOf(geomMap.get("type"));
		requireKnownType(type, no);
		// 先扫原始坐标：越界与非数必须在建 JTS 对象之前拦住，
		// 否则 187°/95° 这种会被后续运算当成合法值一路带下去
		scanCoords(geomMap.get("coordinates"), no);
		boolean closed = closeRings(geomMap, no, warnings);

		Geometry geom = codec.fromGeometry(geomMap);
		if (geom == null || geom.isEmpty()) {
			throw new ServiceException(String.format(GisConstants.MSG_TOPO_DEGENERATE, no, "坐标不足或解析为空"));
		}
		requireNonDegenerate(geom, no);

		Geometry fixed = fixIfInvalid(geom, no, warnings);
		if (fixed == null && !closed) {
			return feature;
		}
		Map<String, Object> out = new LinkedHashMap<>(feature);
		out.put("geometry", codec.toGeometry(fixed == null ? geom : fixed));
		return out;
	}

	// ==================== 硬拒收 ====================

	private static void requireKnownType(String type, int no) {
		switch (type) {
			case "Point", "MultiPoint", "LineString", "MultiLineString", "Polygon", "MultiPolygon" -> {
				// 支持
			}
			default -> throw new ServiceException(String.format(GisConstants.MSG_TOPO_TYPE, no, type));
		}
	}

	/** 递归到最内层的 [lon, lat] 对，逐点判有限性与经纬度范围 */
	private static void scanCoords(Object coords, int no) {
		if (!(coords instanceof List<?> list) || list.isEmpty()) {
			return;
		}
		if (list.get(0) instanceof Number) {
			if (list.size() < 2) {
				throw new ServiceException(String.format(GisConstants.MSG_TOPO_DEGENERATE, no, "坐标对不完整"));
			}
			double lon = ((Number) list.get(0)).doubleValue();
			double lat = ((Number) list.get(1)).doubleValue();
			if (!Double.isFinite(lon) || !Double.isFinite(lat)) {
				throw new ServiceException(String.format(GisConstants.MSG_TOPO_COORD_NAN, no));
			}
			if (lon < -180 || lon > 180 || lat < -90 || lat > 90) {
				throw new ServiceException(String.format(GisConstants.MSG_TOPO_COORD_RANGE, no,
					trim(lon) + ", " + trim(lat)));
			}
			return;
		}
		for (Object item : list) {
			scanCoords(item, no);
		}
	}

	/**
	 * 退化判定按去重后的点数算：GeoJSON 里重复点很常见（导出工具、简化算法都会产），
	 * 三个点里两个重合的「线」实际只有一个位置，落库后长度为 0、缓冲为空，等于脏数据。
	 */
	private static void requireNonDegenerate(Geometry geom, int no) {
		String type = geom.getGeometryType();
		if (type.contains("Point")) {
			return;
		}
		for (int i = 0; i < geom.getNumGeometries(); i++) {
			Geometry part = geom.getGeometryN(i);
			int distinct = distinctCount(part);
			if (type.contains("Line") && distinct < 2) {
				throw new ServiceException(String.format(GisConstants.MSG_TOPO_DEGENERATE, no, "线至少需要两个不同的点"));
			}
			if (type.contains("Polygon") && distinct < 3) {
				throw new ServiceException(String.format(GisConstants.MSG_TOPO_DEGENERATE, no, "面的环至少需要三个不同的点"));
			}
		}
	}

	private static int distinctCount(Geometry geom) {
		List<String> seen = new ArrayList<>();
		for (var c : geom.getCoordinates()) {
			String key = trim(c.x) + "," + trim(c.y);
			if (!seen.contains(key)) {
				seen.add(key);
			}
		}
		return seen.size();
	}

	// ==================== 修复并回报 ====================

	/** 环未闭合就补上首点。GeoJSON 规范要求闭合，但导出工具经常漏掉最后一点 */
	private static boolean closeRings(Map<?, ?> geomMap, int no, List<String> warnings) {
		String type = String.valueOf(geomMap.get("type"));
		if (!type.contains("Polygon")) {
			return false;
		}
		boolean any = ringsUnclosed(geomMap.get("coordinates"), type.startsWith("Multi") ? 2 : 1);
		if (any) {
			warnings.add(String.format(GisConstants.WARN_TOPO_RING_CLOSED, no));
		}
		return any;
	}

	/** depth 1 表示当前层是环的列表，2 表示是多边形的列表 */
	private static boolean ringsUnclosed(Object coords, int depth) {
		if (!(coords instanceof List<?> list)) {
			return false;
		}
		if (depth > 1) {
			boolean any = false;
			for (Object item : list) {
				any |= ringsUnclosed(item, depth - 1);
			}
			return any;
		}
		boolean any = false;
		for (Object ring : list) {
			if (ring instanceof List<?> pts && pts.size() >= 3
				&& !samePoint(pts.get(0), pts.get(pts.size() - 1))) {
				any = true;
			}
		}
		return any;
	}

	private static boolean samePoint(Object a, Object b) {
		if (!(a instanceof List<?> p) || !(b instanceof List<?> q) || p.size() < 2 || q.size() < 2) {
			return false;
		}
		return p.get(0) instanceof Number px && q.get(0) instanceof Number qx
			&& p.get(1) instanceof Number py && q.get(1) instanceof Number qy
			&& px.doubleValue() == qx.doubleValue() && py.doubleValue() == qy.doubleValue();
	}

	/** 返回修复后的几何；本来就有效则返回 null（表示不必改写） */
	private Geometry fixIfInvalid(Geometry geom, int no, List<String> warnings) {
		if (new IsValidOp(geom).isValid()) {
			return null;
		}
		Geometry fixed = GeometryFixer.fix(geom);
		if (fixed == null || fixed.isEmpty()) {
			throw new ServiceException(String.format(GisConstants.MSG_TOPO_UNFIXABLE, no));
		}
		warnings.add(String.format(GisConstants.WARN_TOPO_SELF_INTERSECT, no));
		return fixed;
	}

	private static String trim(double v) {
		return String.valueOf(Math.round(v * 1e6) / 1e6);
	}
}
