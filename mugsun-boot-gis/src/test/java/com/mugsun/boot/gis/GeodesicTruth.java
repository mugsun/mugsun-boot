package com.mugsun.boot.gis;

import org.locationtech.jts.densify.Densifier;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

/** 测试用「真值」：WGS84 椭球 Vincenty 反算测地距离，以及缓冲边界到原要素的测地距离误差。 */
final class GeodesicTruth {

	private static final double A = 6_378_137d;
	private static final double F = 1 / 298.257223563;
	private static final double B = A * (1 - F);
	static final GeometryFactory GF = new GeometryFactory();

	private GeodesicTruth() {
	}

	/** Vincenty 反算（米），收敛到 1e-12 弧度 */
	static double vincenty(double lon1, double lat1, double lon2, double lat2) {
		double l = Math.toRadians(lon2 - lon1);
		double u1 = Math.atan((1 - F) * Math.tan(Math.toRadians(lat1)));
		double u2 = Math.atan((1 - F) * Math.tan(Math.toRadians(lat2)));
		double sinU1 = Math.sin(u1), cosU1 = Math.cos(u1), sinU2 = Math.sin(u2), cosU2 = Math.cos(u2);
		double lambda = l, prev, sinSigma, cosSigma, sigma, cosSqAlpha, cos2SigmaM;
		int iter = 0;
		do {
			double sinL = Math.sin(lambda), cosL = Math.cos(lambda);
			sinSigma = Math.sqrt(Math.pow(cosU2 * sinL, 2) + Math.pow(cosU1 * sinU2 - sinU1 * cosU2 * cosL, 2));
			if (sinSigma == 0) {
				return 0;
			}
			cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosL;
			sigma = Math.atan2(sinSigma, cosSigma);
			double sinAlpha = cosU1 * cosU2 * sinL / sinSigma;
			cosSqAlpha = 1 - sinAlpha * sinAlpha;
			cos2SigmaM = cosSqAlpha == 0 ? 0 : cosSigma - 2 * sinU1 * sinU2 / cosSqAlpha;
			double c = F / 16 * cosSqAlpha * (4 + F * (4 - 3 * cosSqAlpha));
			prev = lambda;
			lambda = l + (1 - c) * F * sinAlpha
				* (sigma + c * sinSigma * (cos2SigmaM + c * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)));
		} while (Math.abs(lambda - prev) > 1e-12 && ++iter < 200);
		double uSq = cosSqAlpha * (A * A - B * B) / (B * B);
		double bigA = 1 + uSq / 16384 * (4096 + uSq * (-768 + uSq * (320 - 175 * uSq)));
		double bigB = uSq / 1024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)));
		double dSigma = bigB * sinSigma * (cos2SigmaM + bigB / 4 * (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)
			- bigB / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2SigmaM * cos2SigmaM)));
		return B * bigA * (sigma - dSigma);
	}

	/**
	 * 缓冲外环每个顶点到原要素（线/面取边界，点取自身）的最小测地距离，返回 {最小相对误差, 最大相对误差}。
	 * 原要素按 step 度加密后逐点取最小，step 远小于缓冲距离时离散误差可忽略。
	 */
	static double[] relErrorRange(Geometry feature, Geometry buffer, double meters, double stepDeg) {
		Geometry target = feature.getDimension() == 2 ? feature.getBoundary() : feature;
		Coordinate[] pts = feature.getDimension() == 0 ? target.getCoordinates()
			: Densifier.densify(target, stepDeg).getCoordinates();
		Polygon outer = (Polygon) (buffer instanceof Polygon ? buffer : buffer.getGeometryN(0));
		double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
		for (Coordinate v : outer.getExteriorRing().getCoordinates()) {
			double best = Double.MAX_VALUE;
			for (Coordinate p : pts) {
				best = Math.min(best, vincenty(v.x, v.y, p.x, p.y));
			}
			double rel = best / meters - 1d;
			lo = Math.min(lo, rel);
			hi = Math.max(hi, rel);
		}
		return new double[] { lo, hi };
	}
}
