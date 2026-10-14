package com.mugsun.boot.gis;

import com.mugsun.core.tool.exception.ServiceException;
import org.locationtech.jts.densify.Densifier;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.jts.operation.distance.DistanceOp;

/**
 * 回落路径（无 PostGIS / forceJava）的米制计算：按要素局部做方位等距投影（AEQD）后在平面上算。
 *
 * <p>早先统一投到 Web Mercator 再按米缓冲 / 量距 / 量面积。Mercator 的米没有纬度修正，地面实际
 * 半径约为设定值 × cos(纬度)（北京约 77%，70°N 约 34%），面积偏差是 cos² 倍，85° 以上还会被截断。
 *
 * <p>AEQD 以要素包络中心（或查询点）为原点：从原点出发的距离严格等于球面大圆距离，离原点 ρ 处的
 * 横向比例误差约 ρ²/(6R²)（200 km 时约 0.02%）。球半径取原点纬度的高斯曲率半径 √(MN)。缓冲结果与
 * WGS84 椭球测地距离的差异在 0.5% 以内（赤道最大，见 {@code GisGeodesicTest} 实测）。
 * 经纬度直线在投影面上是曲线，所以投影前按 {@link #DENSIFY_DEG} 加密、投回前按 {@link #DENSIFY_M} 加密。
 *
 * <p>距离：先在投影面上求最近点对（任意最近点对：顶点、边上任意点、面内为 0），再把这两点投回经纬度，
 * 用 WGS84 Vincenty 反算（近对蹠点不收敛时回落大圆）。
 *
 * <p>适用范围：要素或缓冲范围进入极区（|纬度| > {@link GisConstants#GEODESIC_POLAR_LAT}°）或跨越 180° 经线时
 * 抛业务异常，不静默给出错误结果——这是分析单个几何时的行为。图层查询用 {@link #distanceLenient} /
 * {@link #tryBuffer}，把这类要素单独处理（回落 haversine 或跳过），不让一个要素拖垮整次查询。
 * 空几何：距离为 +∞，缓冲为空几何，面积 / 长度为 0。
 */
public final class GisGeodesic {

	/** WGS84 长半轴 */
	private static final double A = 6_378_137d;
	/** WGS84 第一偏心率平方 */
	private static final double E2 = 6.69437999014e-3;
	/** 投影前经纬度加密步长（度），约 1 km */
	static final double DENSIFY_DEG = 0.01d;
	/** 投回前平面加密步长（米） */
	static final double DENSIFY_M = 1_000d;
	/** 每 1/4 圆 16 段（JTS 默认 8 段），边界更贴近真实圆 */
	private static final int QUADRANT_SEGMENTS = 16;

	private static final GeometryFactory FACTORY = new GeometryFactory();
	/** 每度纬度的最短地面距离（赤道处子午线弧长），用作宽松换算的下限 */
	static final double M_PER_DEG_MIN = 110_574d;
	private static final double F = 1 / 298.257223563;
	private static final double B = A * (1 - F);
	private static final double MEAN_R = 6_371_008.8d;

	private GisGeodesic() {
	}

	/** 纬度 lat（度）处的高斯曲率半径 √(MN)，单位米 */
	public static double gaussianRadius(double lat) {
		double s = Math.sin(Math.toRadians(lat));
		return A * Math.sqrt(1d - E2) / (1d - E2 * s * s);
	}

	/** WGS84 几何按地面米缓冲，返回 WGS84 几何；空几何返回空面 */
	public static Geometry buffer(Geometry wgs, double meters) {
		if (wgs == null || wgs.isEmpty()) {
			return FACTORY.createPolygon();
		}
		checkRange(wgs.getEnvelopeInternal(), meters);
		Aeqd p = Aeqd.around(wgs);
		Geometry plane = p.forward(densifyDeg(wgs));
		Geometry buf = BufferOp.bufferOp(plane, meters, QUADRANT_SEGMENTS, BufferParameters.CAP_ROUND);
		if (buf == null || buf.isEmpty()) {
			return FACTORY.createPolygon();
		}
		return p.inverse(Densifier.densify(buf, DENSIFY_M));
	}

	/** 点 (lon, lat) 到几何的地面最近距离（米）；点在面内为 0，空几何为 +∞ */
	public static double distance(Geometry wgs, double lon, double lat) {
		if (wgs == null || wgs.isEmpty()) {
			return Double.POSITIVE_INFINITY;
		}
		checkRange(new Envelope(lon, lon, lat, lat), 0d);
		checkRange(wgs.getEnvelopeInternal(), 0d);
		Aeqd p = new Aeqd(lon, lat);
		Geometry plane = p.forward(densifyDeg(wgs));
		Coordinate[] pair = DistanceOp.nearestPoints(plane, FACTORY.createPoint(new Coordinate(0d, 0d)));
		if (pair[0].distance(pair[1]) == 0d) {
			return 0d;
		}
		double[] near = p.inverse(pair[0].x, pair[0].y);
		return vincenty(lon, lat, near[0], near[1]);
	}

	/**
	 * 图层查询用的距离：要素在极区或跨 180° 经线时不抛异常，改为以查询点为原点投影求几何上的最近点，
	 * 再用 haversine 大圆距离；仍算不出（投影退化、拓扑异常、非有限值）返回 {@link Double#NaN}，调用方跳过。
	 * 查询点本身须先经 {@link #checkPoint} 校验。
	 */
	public static double distanceLenient(Geometry wgs, double lon, double lat) {
		if (wgs == null || wgs.isEmpty()) {
			return Double.POSITIVE_INFINITY;
		}
		try {
			return distance(wgs, lon, lat);
		} catch (ServiceException outOfRange) {
			// 落到下面的 haversine 回落
		}
		try {
			Aeqd p = new Aeqd(lon, lat);
			Coordinate[] pair = DistanceOp.nearestPoints(p.forward(densifyDeg(wgs)),
				FACTORY.createPoint(new Coordinate(0d, 0d)));
			if (pair[0].distance(pair[1]) == 0d) {
				return 0d;
			}
			double[] near = p.inverse(pair[0].x, pair[0].y);
			double d = greatCircle(lon, lat, near[0], near[1]);
			return Double.isFinite(d) ? d : Double.NaN;
		} catch (RuntimeException e) {
			return Double.NaN;
		}
	}

	/** 图层查询用的缓冲：要素（含外扩）在极区或跨 180° 经线时返回 null，调用方跳过；其余同 {@link #buffer} */
	public static Geometry tryBuffer(Geometry wgs, double meters) {
		try {
			return buffer(wgs, meters);
		} catch (ServiceException outOfRange) {
			return null;
		}
	}

	/** 查询点校验：在极区（|纬度| > 85°）抛业务异常；单点不存在跨经线问题 */
	public static void checkPoint(double lon, double lat) {
		if (Math.abs(lat) > GisConstants.GEODESIC_POLAR_LAT) {
			throw new ServiceException(GisConstants.MSG_GEODESIC_POLAR_POINT);
		}
		checkRange(new Envelope(lon, lon, lat, lat), 0d);
	}

	/** 两个几何之间的地面最近距离（米）；任一为空返回 +∞ */
	public static double distance(Geometry a, Geometry b) {
		if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
			return Double.POSITIVE_INFINITY;
		}
		Envelope env = new Envelope(a.getEnvelopeInternal());
		env.expandToInclude(b.getEnvelopeInternal());
		checkRange(env, 0d);
		Aeqd p = new Aeqd(env.centre().x, env.centre().y);
		Coordinate[] pair = DistanceOp.nearestPoints(p.forward(densifyDeg(a)), p.forward(densifyDeg(b)));
		if (pair[0].distance(pair[1]) == 0d) {
			return 0d;
		}
		double[] u = p.inverse(pair[0].x, pair[0].y);
		double[] v = p.inverse(pair[1].x, pair[1].y);
		return vincenty(u[0], u[1], v[0], v[1]);
	}

	/** 地面面积（平方米）；空几何为 0 */
	public static double area(Geometry wgs) {
		if (wgs == null || wgs.isEmpty()) {
			return 0d;
		}
		checkRange(wgs.getEnvelopeInternal(), 0d);
		return Aeqd.around(wgs).forward(densifyDeg(wgs)).getArea();
	}

	/** 地面长度（米）；空几何为 0 */
	public static double length(Geometry wgs) {
		if (wgs == null || wgs.isEmpty()) {
			return 0d;
		}
		checkRange(wgs.getEnvelopeInternal(), 0d);
		return Aeqd.around(wgs).forward(densifyDeg(wgs)).getLength();
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

	/**
	 * 适用范围检查：包络外扩 reach 米后进入极区或跨越 180° 经线即抛业务异常。
	 * 跨经线判定：包络本身宽于 180°（典型的 179° 与 -179° 两点被读成整圈），或外扩后越过 ±180°。
	 */
	static void checkRange(Envelope env, double reach) {
		double dLat = reach / M_PER_DEG_MIN * 1.01d;
		double top = Math.max(Math.abs(env.getMinY()), Math.abs(env.getMaxY())) + dLat;
		if (top > GisConstants.GEODESIC_POLAR_LAT) {
			throw new ServiceException(GisConstants.MSG_GEODESIC_POLAR);
		}
		double dLon = dLat / Math.cos(Math.toRadians(top));
		if (env.getWidth() > 180d || env.getMinX() - dLon < -180d || env.getMaxX() + dLon > 180d) {
			throw new ServiceException(GisConstants.MSG_GEODESIC_ANTIMERIDIAN);
		}
	}

	/**
	 * WGS84 Vincenty 反算（米）。近对蹠点 200 次迭代不收敛时回落大圆距离（平均半径），
	 * 本模块的适用范围（非极区、不跨经线、单次 ≤ 数百公里）内不会触发。
	 */
	public static double vincenty(double lon1, double lat1, double lon2, double lat2) {
		double l = Math.toRadians(lon2 - lon1);
		double u1 = Math.atan((1 - F) * Math.tan(Math.toRadians(lat1)));
		double u2 = Math.atan((1 - F) * Math.tan(Math.toRadians(lat2)));
		double sinU1 = Math.sin(u1);
		double cosU1 = Math.cos(u1);
		double sinU2 = Math.sin(u2);
		double cosU2 = Math.cos(u2);
		double lambda = l;
		double prev;
		double sinSigma;
		double cosSigma;
		double sigma;
		double cosSqAlpha;
		double cos2SigmaM;
		int iter = 0;
		do {
			double sinL = Math.sin(lambda);
			double cosL = Math.cos(lambda);
			double t1 = cosU2 * sinL;
			double t2 = cosU1 * sinU2 - sinU1 * cosU2 * cosL;
			sinSigma = Math.sqrt(t1 * t1 + t2 * t2);
			if (sinSigma == 0d) {
				return 0d;
			}
			cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosL;
			sigma = Math.atan2(sinSigma, cosSigma);
			double sinAlpha = cosU1 * cosU2 * sinL / sinSigma;
			cosSqAlpha = 1 - sinAlpha * sinAlpha;
			cos2SigmaM = cosSqAlpha == 0d ? 0d : cosSigma - 2 * sinU1 * sinU2 / cosSqAlpha;
			double c = F / 16 * cosSqAlpha * (4 + F * (4 - 3 * cosSqAlpha));
			prev = lambda;
			lambda = l + (1 - c) * F * sinAlpha
				* (sigma + c * sinSigma * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)));
			if (++iter > 200) {
				return greatCircle(lon1, lat1, lon2, lat2);
			}
		} while (Math.abs(lambda - prev) > 1e-12);
		double uSq = cosSqAlpha * (A * A - B * B) / (B * B);
		double bigA = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)));
		double bigB = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)));
		double dSigma = bigB * sinSigma * (cos2SigmaM + bigB / 4 * (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)
			- bigB / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2SigmaM * cos2SigmaM)));
		return B * bigA * (sigma - dSigma);
	}

	/** 大圆距离（haversine，平均半径），只作 Vincenty 不收敛时的回落 */
	static double greatCircle(double lon1, double lat1, double lon2, double lat2) {
		double dLat = Math.toRadians(lat2 - lat1);
		double dLon = Math.toRadians(lon2 - lon1);
		double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
			+ Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
		return 2 * MEAN_R * Math.asin(Math.min(1d, Math.sqrt(a)));
	}

	private static Geometry densifyDeg(Geometry wgs) {
		if (wgs.getDimension() == 0) {
			return wgs;
		}
		return Densifier.densify(wgs, DENSIFY_DEG);
	}

	/** 球面方位等距投影（Snyder 1987, §25），原点 (lon0, lat0)，半径取原点高斯半径 */
	static final class Aeqd {

		private final double lon0;
		private final double sinLat0;
		private final double cosLat0;
		private final double r;

		Aeqd(double lon0, double lat0) {
			this.lon0 = Math.toRadians(lon0);
			double phi0 = Math.toRadians(lat0);
			this.sinLat0 = Math.sin(phi0);
			this.cosLat0 = Math.cos(phi0);
			this.r = gaussianRadius(lat0);
		}

		static Aeqd around(Geometry wgs) {
			Coordinate c = wgs.getEnvelopeInternal().centre();
			return new Aeqd(c.x, c.y);
		}

		double[] forward(double lon, double lat) {
			double phi = Math.toRadians(lat);
			double dl = Math.toRadians(lon) - lon0;
			double sinPhi = Math.sin(phi);
			double cosPhi = Math.cos(phi);
			double cosDl = Math.cos(dl);
			double cosc = Math.max(-1d, Math.min(1d, sinLat0 * sinPhi + cosLat0 * cosPhi * cosDl));
			double c = Math.acos(cosc);
			double k = c < 1e-12 ? 1d : c / Math.sin(c);
			double x = r * k * cosPhi * Math.sin(dl);
			double y = r * k * (cosLat0 * sinPhi - sinLat0 * cosPhi * cosDl);
			return new double[] { x, y };
		}

		double[] inverse(double x, double y) {
			double rho = Math.hypot(x, y);
			if (rho < 1e-9) {
				return new double[] { Math.toDegrees(lon0), Math.toDegrees(Math.asin(sinLat0)) };
			}
			double c = rho / r;
			double sinC = Math.sin(c);
			double cosC = Math.cos(c);
			double phi = Math.asin(Math.max(-1d, Math.min(1d, cosC * sinLat0 + y * sinC * cosLat0 / rho)));
			double lambda = lon0 + Math.atan2(x * sinC, rho * cosLat0 * cosC - y * sinLat0 * sinC);
			double lon = Math.toDegrees(lambda);
			if (lon > 180d) {
				lon -= 360d;
			} else if (lon < -180d) {
				lon += 360d;
			}
			return new double[] { lon, Math.toDegrees(phi) };
		}

		Geometry forward(Geometry wgs) {
			return apply(wgs, true);
		}

		Geometry inverse(Geometry plane) {
			return apply(plane, false);
		}

		private Geometry apply(Geometry g, boolean fwd) {
			Geometry copy = g.copy();
			copy.apply((CoordinateFilter) c -> {
				double[] v = fwd ? forward(c.x, c.y) : inverse(c.x, c.y);
				c.setX(v[0]);
				c.setY(v[1]);
			});
			copy.geometryChanged();
			return copy;
		}
	}
}
