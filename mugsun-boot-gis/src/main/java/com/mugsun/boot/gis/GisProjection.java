package com.mugsun.boot.gis;

import net.sf.geographiclib.Geodesic;
import net.sf.geographiclib.GeodesicData;
import net.sf.geographiclib.GeodesicMask;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;

/**
 * 回落路径缓冲 / 量距用的椭球方位等距投影（AEQD），完全由 GeographicLib 定义：
 * 正算取 {@code Inverse(原点, 点)} 的距离 s12 与方位角 azi1，x = s12·sin(azi1)、y = s12·cos(azi1)；
 * 反算取 {@code Direct(原点, atan2(x, y), hypot(x, y))}。从原点出发的距离就是 WGS84 测地距离，原点在极点也成立。
 * 没用 proj4j 的 {@code +proj=aeqd}：1.4.3 的椭球反算在 lon_0 接近 ±180° 时结果错误（实测 lon_0=179.9 反算 -179.9 得 -0.1）。
 *
 * <p>原点选法：包络中心 |纬度| ≥ {@link #POLAR_AEQD_LAT}° 时用极点，其余用包络中心。不沿用 PostGIS
 * {@code geography_bestsrid} 的 70° 极区阈值：AEQD 横向比例误差约 c²/6（c 为离原点的角距），72°N 的要素离极点 18°，
 * 用极点 AEQD 缓冲会偏小约 1.7%（实测），而包络中心 AEQD 在任意纬度都成立。
 *
 * <p>面积不走投影，直接用 GeographicLib {@code PolygonArea}（边按测地线），见 {@link GisGeodesic#area}。
 * 本类无状态、线程安全，原点不取整。
 */
final class GisProjection {

	/** 缓冲 / 量距改用极点 AEQD 的纬度阈值 */
	static final double POLAR_AEQD_LAT = 88d;

	/** 原点经度：反投影结果按它展开到 [lon0-180, lon0+180]，跨 180° 时仍连续 */
	private final double lon0;
	private final double lat0;

	private GisProjection(double lon0, double lat0) {
		this.lon0 = lon0;
		this.lat0 = lat0;
	}

	/** 以 (lon0, lat0) 为原点的椭球 AEQD */
	static GisProjection aeqd(double lon0, double lat0) {
		return new GisProjection(lon0, lat0);
	}

	/** 缓冲 / 两几何量距用：包络中心 |纬度| ≥ 88° 为极点 AEQD，否则包络中心 AEQD */
	static GisProjection forDistance(Envelope env) {
		double cy = env.centre().y;
		if (Math.abs(cy) >= POLAR_AEQD_LAT) {
			return cy > 0 ? aeqd(0d, 90d) : aeqd(0d, -90d);
		}
		return aeqd(env.centre().x, env.centre().y);
	}

	double originLon() {
		return lon0;
	}

	double originLat() {
		return lat0;
	}

	double[] forward(double lon, double lat) {
		GeodesicData g = Geodesic.WGS84.Inverse(lat0, lon0, lat, lon, GeodesicMask.DISTANCE | GeodesicMask.AZIMUTH);
		double azi = Math.toRadians(g.azi1);
		return new double[] { g.s12 * Math.sin(azi), g.s12 * Math.cos(azi) };
	}

	double[] inverse(double x, double y) {
		GeodesicData g = Geodesic.WGS84.Direct(lat0, lon0, Math.toDegrees(Math.atan2(x, y)), Math.hypot(x, y),
			GeodesicMask.LATITUDE | GeodesicMask.LONGITUDE);
		double lon = g.lon2;
		while (lon - lon0 > 180d) {
			lon -= 360d;
		}
		while (lon - lon0 < -180d) {
			lon += 360d;
		}
		return new double[] { lon, g.lat2 };
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
