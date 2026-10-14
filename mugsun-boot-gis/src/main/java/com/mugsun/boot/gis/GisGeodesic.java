package com.mugsun.boot.gis;

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
 * 横向比例误差约 ρ²/(6R²)（200 km 时约 0.02%）。球半径取原点纬度的高斯曲率半径 √(MN)，与 WGS84
 * 椭球测地距离的方向性差异在 ±0.35% 以内（赤道最大，见 {@code GisGeodesicTest} 实测）。
 * 经纬度直线在投影面上是曲线，所以投影前按 {@link #DENSIFY_DEG} 加密、投回前按 {@link #DENSIFY_M} 加密。
 *
 * <p>不处理跨 180° 经线的要素（与本模块其余部分一致）。
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
	/** 与 PostGIS ST_Buffer 默认一致：每 1/4 圆 8 段；米制缓冲加到 16 段，边界更贴近真实圆 */
	private static final int QUADRANT_SEGMENTS = 16;

	private static final GeometryFactory FACTORY = new GeometryFactory();

	private GisGeodesic() {
	}

	/** 纬度 lat（度）处的高斯曲率半径 √(MN)，单位米 */
	public static double gaussianRadius(double lat) {
		double s = Math.sin(Math.toRadians(lat));
		return A * Math.sqrt(1d - E2) / (1d - E2 * s * s);
	}

	/** WGS84 几何按地面米缓冲，返回 WGS84 几何 */
	public static Geometry buffer(Geometry wgs, double meters) {
		Aeqd p = Aeqd.around(wgs);
		Geometry plane = p.forward(densifyDeg(wgs));
		Geometry buf = BufferOp.bufferOp(plane, meters, QUADRANT_SEGMENTS, BufferParameters.CAP_ROUND);
		if (buf == null || buf.isEmpty()) {
			return buf;
		}
		return p.inverse(Densifier.densify(buf, DENSIFY_M));
	}

	/** 点 (lon, lat) 到几何的地面最近距离（米）；点在面内为 0 */
	public static double distance(Geometry wgs, double lon, double lat) {
		Aeqd p = new Aeqd(lon, lat);
		Geometry plane = p.forward(densifyDeg(wgs));
		return DistanceOp.distance(plane, FACTORY.createPoint(new Coordinate(0d, 0d)));
	}

	/** 两个几何之间的地面最近距离（米） */
	public static double distance(Geometry a, Geometry b) {
		Envelope env = new Envelope(a.getEnvelopeInternal());
		env.expandToInclude(b.getEnvelopeInternal());
		Aeqd p = new Aeqd(env.centre().x, env.centre().y);
		return DistanceOp.distance(p.forward(densifyDeg(a)), p.forward(densifyDeg(b)));
	}

	/** 地面面积（平方米） */
	public static double area(Geometry wgs) {
		return Aeqd.around(wgs).forward(densifyDeg(wgs)).getArea();
	}

	/** 地面长度（米） */
	public static double length(Geometry wgs) {
		return Aeqd.around(wgs).forward(densifyDeg(wgs)).getLength();
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
