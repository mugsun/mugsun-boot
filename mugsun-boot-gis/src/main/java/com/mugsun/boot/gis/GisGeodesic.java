package com.mugsun.boot.gis;

import com.mugsun.core.tool.exception.ServiceException;
import net.sf.geographiclib.Geodesic;
import net.sf.geographiclib.GeodesicData;
import net.sf.geographiclib.GeodesicLine;
import net.sf.geographiclib.GeodesicMask;
import net.sf.geographiclib.PolygonArea;
import net.sf.geographiclib.PolygonResult;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.util.GeometryTransformer;
import org.locationtech.jts.geom.util.LinearComponentExtracter;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.distance.DistanceOp;

import java.util.ArrayList;
import java.util.List;

/**
 * 回落路径（无 PostGIS / forceJava）的米制计算，全部建立在成熟开源库上：
 * <ul>
 *   <li>测地距离：GeographicLib-Java（Karney，MIT），与 PostGIS 2.2+ {@code geography} 同一算法；</li>
 *   <li>面积：GeographicLib {@code PolygonArea}（边按测地线），与 PostGIS {@code ST_Area(geography)} 同一算法；</li>
 *   <li>局部投影：由 GeographicLib 正反算定义的椭球 AEQD，见 {@link GisProjection}；</li>
 *   <li>平面缓冲 / 最近点对：JTS。</li>
 * </ul>
 *
 * <p>早先统一投到 Web Mercator 再按米缓冲 / 量距 / 量面积，没有纬度修正（北京 500 m 缓冲实际约 383 m）；
 * 后来改用自写的球面 AEQD，85° 以上不支持。现在改为椭球投影：从原点出发的距离就是测地距离，
 * 极区用以极点为原点的投影，没有纬度限制。缓冲边界实测误差见 GisGeodesicTest（0°–88°、500 m–200 km 在 −0.05%～+0.05%）。
 * 与 PostGIS geography 一样把两顶点间的边当作测地线：投影前、投回后都沿 {@link GeodesicLine} 每 {@link #DENSIFY_M} 米取点加密。
 *
 * <ul>
 *   <li>缓冲：包络中心 |纬度| ≥ 88° 用极点 AEQD，否则包络中心 AEQD（椭球 AEQD 在任意纬度都成立）；</li>
 *   <li>距离：以查询点（或两几何包络）为原点的 AEQD 求任意最近点对，再对这一对点做 {@link #inverse}；</li>
 *   <li>面积：{@code PolygonArea} 外环减内环；</li>
 *   <li>长度：逐段 {@link #inverse} 求和（边即测地线，无需加密）。</li>
 * </ul>
 *
 * <p>适用范围（分析单个几何时抛业务异常，图层查询走 {@link #distanceLenient} / {@link #tryBuffer} 单独处理）：
 * 缓冲结果包住极点；要素跨越 180° 经线或缓冲结果越过 180°。空几何：距离为 +∞，缓冲为空几何，面积 / 长度为 0。
 */
public final class GisGeodesic {

	/** 沿测地线加密的步长（米） */
	static final double DENSIFY_M = 1_000d;
	/**
	 * 每 1/4 圆 32 段（JTS 默认 8 段）。折线弦会向内凹 R·(1−cos(π/4/n))：16 段约 0.12%，32 段约 0.03%。
	 * 椭球投影把其它误差压下去之后，这一项成了主导，所以从 16 提到 32。
	 */
	private static final int QUADRANT_SEGMENTS = 32;

	private static final GeometryFactory FACTORY = new GeometryFactory();
	/** 每度纬度的最短地面距离（赤道处子午线弧长），用作宽松换算的下限 */
	static final double M_PER_DEG_MIN = 110_574d;

	private GisGeodesic() {
	}

	/**
	 * WGS84 几何按地面米缓冲，返回 WGS84 几何；空几何返回空面。
	 * 缓冲结果包住极点，或要素 / 结果跨越 180° 经线时抛 {@link ServiceException}。
	 */
	public static Geometry buffer(Geometry wgs, double meters) {
		if (wgs == null || wgs.isEmpty()) {
			return FACTORY.createPolygon();
		}
		requireNoAntimeridian(wgs);
		GisProjection p = GisProjection.forDistance(wgs.getEnvelopeInternal());
		Geometry plane = p.forward(densify(wgs));
		Geometry buf = BufferOp.bufferOp(plane, meters, QUADRANT_SEGMENTS, BufferParameters.CAP_ROUND);
		if (buf == null || buf.isEmpty()) {
			return FACTORY.createPolygon();
		}
		for (double poleLat : new double[] { 90d, -90d }) {
			double[] pole = p.forward(0d, poleLat);
			if (Double.isFinite(pole[0]) && Double.isFinite(pole[1])
				&& buf.covers(FACTORY.createPoint(new Coordinate(pole[0], pole[1])))) {
				throw new ServiceException(GisConstants.MSG_GEODESIC_POLE);
			}
		}
		Geometry out = densify(p.inverse(buf));
		if (outOfLonRange(out) || crossesAntimeridian(out)) {
			throw new ServiceException(GisConstants.MSG_GEODESIC_ANTIMERIDIAN);
		}
		return out;
	}

	/** 图层查询用的缓冲：结果包住极点或跨 180° 经线时返回 null，调用方跳过；其余同 {@link #buffer} */
	public static Geometry tryBuffer(Geometry wgs, double meters) {
		try {
			return buffer(wgs, meters);
		} catch (ServiceException outOfRange) {
			return null;
		}
	}

	/** 点 (lon, lat) 到几何的地面最近距离（米）；点在面内为 0，空几何为 +∞；要素跨 180° 经线时抛异常 */
	public static double distance(Geometry wgs, double lon, double lat) {
		if (wgs == null || wgs.isEmpty()) {
			return Double.POSITIVE_INFINITY;
		}
		requireNoAntimeridian(wgs);
		GisProjection p = GisProjection.aeqd(lon, lat);
		Geometry plane = p.forward(densify(wgs));
		double[] origin = p.forward(lon, lat);
		Coordinate[] pair = DistanceOp.nearestPoints(plane, FACTORY.createPoint(new Coordinate(origin[0], origin[1])));
		if (pair[0].distance(pair[1]) == 0d) {
			return 0d;
		}
		double[] near = p.inverse(pair[0].x, pair[0].y);
		return inverse(lon, lat, near[0], near[1]);
	}

	/**
	 * 图层查询用的距离：要素跨 180° 经线时不抛异常。
	 *
	 * <p>跨 180° 的要素（相邻顶点经度差超过 180°）先把负经度加 360，变成连续的经度范围再加密，避免绕地球走长路；
	 * 查询点也换到同一范围，取 lon 与 lon ± 360 里离要素包络中心较近的那个表示。
	 * 最近点以查询点为原点投影求出，再用逐顶点（加密后）测地距离最小值兜底校验，投影结果不可信时改用顶点最小值。
	 * 仍算不出（平移后仍宽于 180°、非有限值、拓扑异常）返回 {@link Double#NaN}，调用方跳过。
	 */
	public static double distanceLenient(Geometry wgs, double lon, double lat) {
		if (wgs == null || wgs.isEmpty()) {
			return Double.POSITIVE_INFINITY;
		}
		try {
			return distance(wgs, lon, lat);
		} catch (ServiceException outOfRange) {
			// 落到下面的回落
		}
		try {
			Geometry g = crossesAntimeridian(wgs) ? shiftNegativeLon(wgs) : wgs;
			Envelope env = g.getEnvelopeInternal();
			if (env.getWidth() > 180d) {
				return Double.NaN;
			}
			double cx = env.centre().x;
			double qLon = lon;
			for (double cand : new double[] { lon - 360d, lon + 360d }) {
				if (Math.abs(cand - cx) < Math.abs(qLon - cx)) {
					qLon = cand;
				}
			}
			Geometry dense = densify(g);
			if (g.getDimension() == 2 && g.covers(FACTORY.createPoint(new Coordinate(qLon, lat)))) {
				return 0d;
			}
			double vertexMin = Double.POSITIVE_INFINITY;
			for (Coordinate c : dense.getCoordinates()) {
				vertexMin = Math.min(vertexMin, inverse(qLon, lat, c.x, c.y));
			}
			if (!Double.isFinite(vertexMin)) {
				return Double.NaN;
			}
			double proj = Double.NaN;
			try {
				GisProjection p = GisProjection.aeqd(qLon, lat);
				double[] origin = p.forward(qLon, lat);
				Coordinate[] pair = DistanceOp.nearestPoints(p.forward(dense),
					FACTORY.createPoint(new Coordinate(origin[0], origin[1])));
				double[] near = p.inverse(pair[0].x, pair[0].y);
				proj = inverse(qLon, lat, near[0], near[1]);
			} catch (RuntimeException degenerate) {
				// 投影退化，只用顶点最小值
			}
			// 加密步长 DENSIFY_M 下，真实最近点离最近顶点不超过半步；超出这个范围说明投影结果不可信
			double slack = DENSIFY_M;
			if (Double.isFinite(proj) && proj <= vertexMin + 1d && proj >= vertexMin - slack) {
				return proj;
			}
			return vertexMin;
		} catch (RuntimeException e) {
			return Double.NaN;
		}
	}

	/** 两个几何之间的地面最近距离（米）；任一为空返回 +∞ */
	public static double distance(Geometry a, Geometry b) {
		if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
			return Double.POSITIVE_INFINITY;
		}
		requireNoAntimeridian(a);
		requireNoAntimeridian(b);
		Envelope env = new Envelope(a.getEnvelopeInternal());
		env.expandToInclude(b.getEnvelopeInternal());
		if (env.getWidth() > 180d) {
			throw new ServiceException(GisConstants.MSG_GEODESIC_ANTIMERIDIAN);
		}
		GisProjection p = GisProjection.forDistance(env);
		Coordinate[] pair = DistanceOp.nearestPoints(p.forward(densify(a)), p.forward(densify(b)));
		if (pair[0].distance(pair[1]) == 0d) {
			return 0d;
		}
		double[] u = p.inverse(pair[0].x, pair[0].y);
		double[] v = p.inverse(pair[1].x, pair[1].y);
		return inverse(u[0], u[1], v[0], v[1]);
	}

	/** 地面面积（平方米）：GeographicLib PolygonArea，边按测地线，逐面外环减内环；空几何为 0 */
	public static double area(Geometry wgs) {
		if (wgs == null || wgs.isEmpty()) {
			return 0d;
		}
		requireNoAntimeridian(wgs);
		if (wgs.getDimension() < 2) {
			return 0d;
		}
		double sum = 0d;
		for (int i = 0; i < wgs.getNumGeometries(); i++) {
			if (wgs.getGeometryN(i) instanceof Polygon poly && !poly.isEmpty()) {
				sum += ringArea(poly.getExteriorRing().getCoordinates());
				for (int k = 0; k < poly.getNumInteriorRing(); k++) {
					sum -= ringArea(poly.getInteriorRingN(k).getCoordinates());
				}
			}
		}
		return sum;
	}

	/** 地面长度（米）：逐段测地距离求和（面取周长），边即测地线；空几何为 0 */
	public static double length(Geometry wgs) {
		if (wgs == null || wgs.isEmpty()) {
			return 0d;
		}
		requireNoAntimeridian(wgs);
		double sum = 0d;
		@SuppressWarnings("unchecked")
		List<LineString> lines = LinearComponentExtracter.getLines(wgs);
		for (LineString line : lines) {
			Coordinate[] cs = line.getCoordinates();
			for (int i = 1; i < cs.length; i++) {
				sum += inverse(cs[i - 1].x, cs[i - 1].y, cs[i].x, cs[i].y);
			}
		}
		return sum;
	}

	/**
	 * WGS84 椭球测地距离（米）：GeographicLib（Karney 2013）反算，与 PostGIS 2.2+ {@code geography} 同一算法。
	 * 任意点对都收敛（含近对蹠点），精度优于 15 nm。注意 GeographicLib 参数是先纬度后经度。
	 */
	public static double inverse(double lon1, double lat1, double lon2, double lat2) {
		return Geodesic.WGS84.Inverse(lat1, lon1, lat2, lon2, GeodesicMask.DISTANCE).s12;
	}

	/**
	 * 宽松地把「离点 (lon, lat) meters 米以内」换算成经纬度包络，用于预筛：只会多收、不会漏。
	 * 纬度按每度最短 {@link #M_PER_DEG_MIN} 米再放大 5%；经度按包络内最高纬度的 cos 放大，接近极点时退化为全经度。
	 */
	public static Envelope looseEnvelope(double lon, double lat, double meters) {
		double dLat = meters / M_PER_DEG_MIN * 1.05d + 1e-6;
		double top = Math.min(90d, Math.abs(lat) + dLat);
		double cos = Math.cos(Math.toRadians(top));
		double dLon = cos < 1e-3 ? 360d : Math.min(360d, dLat / cos);
		return new Envelope(lon - dLon, lon + dLon, lat - dLat, lat + dLat);
	}

	/**
	 * {@link #looseEnvelope} 按 ±180° 拆开：越过 180° 的部分折回对侧单独成框，保证对侧要素不漏；
	 * 经度跨度达到 360° 时退化为全经度一个框。纬度截到 [-90, 90]。
	 */
	public static java.util.List<Envelope> looseEnvelopes(double lon, double lat, double meters) {
		Envelope e = looseEnvelope(lon, lat, meters);
		double minY = Math.max(-90d, e.getMinY());
		double maxY = Math.min(90d, e.getMaxY());
		if (e.getWidth() >= 360d) {
			return java.util.List.of(new Envelope(-180d, 180d, minY, maxY));
		}
		if (e.getMinX() < -180d) {
			return java.util.List.of(new Envelope(-180d, e.getMaxX(), minY, maxY),
				new Envelope(e.getMinX() + 360d, 180d, minY, maxY));
		}
		if (e.getMaxX() > 180d) {
			return java.util.List.of(new Envelope(e.getMinX(), 180d, minY, maxY),
				new Envelope(-180d, e.getMaxX() - 360d, minY, maxY));
		}
		return java.util.List.of(new Envelope(e.getMinX(), e.getMaxX(), minY, maxY));
	}

	/** 点到包络的地面距离下限（米）：只看纬度差，乘每度最短子午线弧长，必然 ≤ 真实测地距离 */
	public static double lowerBound(Envelope env, double lat) {
		double gap = Math.max(0d, Math.max(env.getMinY() - lat, lat - env.getMaxY()));
		return gap * M_PER_DEG_MIN * 0.999d;
	}

	/** 要素本身跨越 180° 经线（相邻顶点经度差超过 180°，或包络宽于 180°）时抛业务异常 */
	static void requireNoAntimeridian(Geometry wgs) {
		if (wgs.getEnvelopeInternal().getWidth() > 180d || crossesAntimeridian(wgs)) {
			throw new ServiceException(GisConstants.MSG_GEODESIC_ANTIMERIDIAN);
		}
	}

	private static boolean outOfLonRange(Geometry g) {
		Envelope e = g.getEnvelopeInternal();
		return e.getMinX() < -180d || e.getMaxX() > 180d;
	}

	/** 是否跨 180° 经线：任一相邻顶点经度差超过 180° */
	static boolean crossesAntimeridian(Geometry wgs) {
		for (int i = 0; i < wgs.getNumGeometries(); i++) {
			Geometry part = wgs.getGeometryN(i);
			if (part instanceof org.locationtech.jts.geom.Polygon poly) {
				if (ringCrosses(poly.getExteriorRing().getCoordinates())) {
					return true;
				}
				for (int k = 0; k < poly.getNumInteriorRing(); k++) {
					if (ringCrosses(poly.getInteriorRingN(k).getCoordinates())) {
						return true;
					}
				}
			} else if (ringCrosses(part.getCoordinates())) {
				return true;
			}
		}
		return false;
	}

	private static boolean ringCrosses(Coordinate[] cs) {
		for (int i = 1; i < cs.length; i++) {
			if (Math.abs(cs[i].x - cs[i - 1].x) > 180d) {
				return true;
			}
		}
		return false;
	}

	/** 负经度加 360，跨 180° 的要素变成 [0, 360) 内的连续几何 */
	static Geometry shiftNegativeLon(Geometry wgs) {
		Geometry copy = wgs.copy();
		copy.apply((CoordinateFilter) c -> {
			if (c.x < 0d) {
				c.setX(c.x + 360d);
			}
		});
		copy.geometryChanged();
		return copy;
	}

	/** 环面积绝对值（平方米），不依赖环的方向 */
	private static double ringArea(Coordinate[] ring) {
		PolygonArea pa = new PolygonArea(Geodesic.WGS84, false);
		for (int i = 0; i < ring.length - 1; i++) {
			pa.AddPoint(ring[i].y, ring[i].x);
		}
		PolygonResult r = pa.Compute(false, true);
		return Math.abs(r.area);
	}

	/** 把每条边当作测地线，沿 {@link GeodesicLine} 每 {@link #DENSIFY_M} 米插点；经度保持与前一点连续 */
	static Geometry densify(Geometry wgs) {
		if (wgs.getDimension() == 0 || wgs.isEmpty()) {
			return wgs;
		}
		return new GeometryTransformer() {
			@Override
			protected CoordinateSequence transformCoordinates(CoordinateSequence seq, Geometry parent) {
				Coordinate[] cs = seq.toCoordinateArray();
				if (cs.length < 2) {
					return factory.getCoordinateSequenceFactory().create(cs);
				}
				List<Coordinate> out = new ArrayList<>(cs.length);
				out.add(new Coordinate(cs[0].x, cs[0].y));
				for (int i = 1; i < cs.length; i++) {
					Coordinate a = cs[i - 1];
					Coordinate b = cs[i];
					GeodesicLine line = Geodesic.WGS84.InverseLine(a.y, a.x, b.y, b.x,
						GeodesicMask.DISTANCE_IN | GeodesicMask.LATITUDE | GeodesicMask.LONGITUDE);
					int n = (int) Math.ceil(line.Distance() / DENSIFY_M);
					for (int k = 1; k < n; k++) {
						GeodesicData d = line.Position(line.Distance() * k / n,
							GeodesicMask.LATITUDE | GeodesicMask.LONGITUDE);
						double prev = out.get(out.size() - 1).x;
						out.add(new Coordinate(d.lon2 - Math.rint((d.lon2 - prev) / 360d) * 360d, d.lat2));
					}
					out.add(new Coordinate(b.x, b.y));
				}
				return factory.getCoordinateSequenceFactory().create(out.toArray(new Coordinate[0]));
			}
		}.transform(wgs);
	}
}
