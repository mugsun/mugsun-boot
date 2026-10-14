package com.mugsun.boot.gis;

import net.sf.geographiclib.Geodesic;
import org.locationtech.jts.densify.Densifier;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;

/** 测试用「真值」：WGS84 椭球 GeographicLib 反算测地距离，以及缓冲边界到原要素的测地距离误差。 */
final class GeodesicTruth {

	static final GeometryFactory GF = new GeometryFactory();

	private GeodesicTruth() {
	}

	/** 测地距离真值（米）：GeographicLib 反算，与 PostGIS geography 同一算法 */
	static double inverse(double lon1, double lat1, double lon2, double lat2) {
		return Geodesic.WGS84.Inverse(lat1, lon1, lat2, lon2).s12;
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
				best = Math.min(best, inverse(v.x, v.y, p.x, p.y));
			}
			double rel = best / meters - 1d;
			lo = Math.min(lo, rel);
			hi = Math.max(hi, rel);
		}
		return new double[] { lo, hi };
	}
}
