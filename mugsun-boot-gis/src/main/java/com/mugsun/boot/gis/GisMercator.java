package com.mugsun.boot.gis;

/**
 * Web Mercator（EPSG:3857）与 WGS84 互转。缓冲/面积/长度先投到平面米制再算，结果再投回经纬度。
 */
public final class GisMercator {

	private static final double RADIUS = 6378137d;

	private GisMercator() {
	}

	public static double[] to3857(double lon, double lat) {
		double clamped = Math.max(-85.05112878, Math.min(85.05112878, lat));
		double x = Math.toRadians(lon) * RADIUS;
		double y = Math.log(Math.tan(Math.PI / 4d + Math.toRadians(clamped) / 2d)) * RADIUS;
		return new double[] { x, y };
	}

	public static double[] to4326(double x, double y) {
		double lon = Math.toDegrees(x / RADIUS);
		double lat = Math.toDegrees(2d * Math.atan(Math.exp(y / RADIUS)) - Math.PI / 2d);
		return new double[] { lon, lat };
	}
}
