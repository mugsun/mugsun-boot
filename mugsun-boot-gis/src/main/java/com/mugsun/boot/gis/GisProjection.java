package com.mugsun.boot.gis;

import net.sf.geographiclib.Geodesic;
import net.sf.geographiclib.GeodesicData;
import net.sf.geographiclib.GeodesicMask;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.ProjCoordinate;
import org.locationtech.proj4j.proj.Projection;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 回落路径的局部投影（proj4j，Apache-2.0），选法参照 PostGIS {@code geography_bestsrid}（只借逻辑，不拷代码）：
 * <ul>
 *   <li>面积：包络中心 |纬度| &gt; 70° 且纬度跨度 &lt; 45° 时用极点 LAEA（与 PostGIS 999061 / 999161 相同参数：
 *       北 lat_0=90 lon_0=-40，南 lat_0=-90 lon_0=0），其余用包络中心 LAEA。LAEA 等积，面积不受离原点远近影响；</li>
 *   <li>缓冲 / 量距：包络中心 |纬度| ≥ {@link #POLAR_AEQD_LAT}° 时用以极点为原点的椭球 AEQD，其余用包络中心的椭球 AEQD。
 *       不沿用 bestsrid 的 70° 阈值：AEQD 横向比例误差约 c²/6（c 为离原点的角距），72°N 的要素离极点 18°，
 *       用极点 AEQD 缓冲会偏小约 1.7%（实测），而包络中心 AEQD 在任意纬度都成立；88° 以上离极点不超过 2°，
 *       加 200 km 缓冲后误差约 0.07%。</li>
 * </ul>
 * 椭球 AEQD 直接由 GeographicLib 定义：正算取 {@code Inverse(原点, 点)} 的距离 s12 与方位角 azi1，
 * x = s12·sin(azi1)、y = s12·cos(azi1)；反算取 {@code Direct(原点, atan2(x, y), hypot(x, y))}。从原点出发的距离
 * 就是 WGS84 测地距离，原点在极点也成立。proj4j 1.4.3 的 {@code +proj=aeqd} 椭球反算在 lon_0 接近 ±180° 时
 * 结果错误（实测 lon_0=179.9 反算 -179.9 得 -0.1），所以 AEQD 不走 proj4j；LAEA 只用正算，仍用 proj4j。
 *
 * <p>proj4j 的 {@link Projection} 实例（LAEA）不保证线程安全，按线程缓存（每线程最多 {@link #CACHE_SIZE} 个，原点取 0.01° 精度）。
 */
final class GisProjection {

	/** 与 PostGIS bestsrid 相同的极区判定阈值 */
	static final double POLAR_CENTER_LAT = 70d;
	static final double POLAR_MAX_HEIGHT = 45d;
	/** 缓冲 / 量距改用极点 AEQD 的纬度阈值 */
	static final double POLAR_AEQD_LAT = 88d;
	private static final int CACHE_SIZE = 64;

	private static final CRSFactory CRS = new CRSFactory();
	private static final ThreadLocal<Map<String, Projection>> CACHE = ThreadLocal.withInitial(
		() -> new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Projection> eldest) {
				return size() > CACHE_SIZE;
			}
		});

	/** LAEA 时为 proj4j 投影；AEQD 时为 null，改用 GeographicLib */
	private final Projection proj;
	/** 原点经度：反投影结果按它展开到 [lon0-180, lon0+180]，跨 180° 时仍连续 */
	private final double lon0;
	private final double lat0;

	private GisProjection(Projection proj, double lon0, double lat0) {
		this.proj = proj;
		this.lon0 = lon0;
		this.lat0 = lat0;
	}

	/** 以 (lon0, lat0) 为原点的椭球 AEQD */
	static GisProjection aeqd(double lon0, double lat0) {
		double lo = round(lon0);
		double la = round(lat0);
		return new GisProjection(null, lo, la);
	}

	/** 以 (lon0, lat0) 为原点的椭球 LAEA（等积） */
	static GisProjection laea(double lon0, double lat0) {
		double lo = round(lon0);
		double la = round(lat0);
		return new GisProjection(get("laea", lo, la), lo, la);
	}

	static boolean polar(Envelope env) {
		double cy = (env.getMinY() + env.getMaxY()) / 2;
		return Math.abs(cy) > POLAR_CENTER_LAT && env.getHeight() < POLAR_MAX_HEIGHT;
	}

	/** 缓冲 / 两几何量距用：包络中心 |纬度| ≥ 88° 为极点 AEQD，否则包络中心 AEQD */
	static GisProjection forDistance(Envelope env) {
		double cy = env.centre().y;
		if (Math.abs(cy) >= POLAR_AEQD_LAT) {
			return cy > 0 ? aeqd(0d, 90d) : aeqd(0d, -90d);
		}
		return aeqd(env.centre().x, env.centre().y);
	}

	/** 面积用：极区为极点 LAEA，否则包络中心 LAEA */
	static GisProjection forArea(Envelope env) {
		if (polar(env)) {
			return env.getMaxY() > 0 ? laea(-40d, 90d) : laea(0d, -90d);
		}
		return laea(env.centre().x, env.centre().y);
	}

	double originLon() {
		return lon0;
	}

	double originLat() {
		return lat0;
	}

	double[] forward(double lon, double lat) {
		if (proj == null) {
			GeodesicData g = Geodesic.WGS84.Inverse(lat0, lon0, lat, lon,
				GeodesicMask.DISTANCE | GeodesicMask.AZIMUTH);
			double azi = Math.toRadians(g.azi1);
			return new double[] { g.s12 * Math.sin(azi), g.s12 * Math.cos(azi) };
		}
		ProjCoordinate out = proj.project(new ProjCoordinate(lon, lat), new ProjCoordinate());
		return new double[] { out.x, out.y };
	}

	double[] inverse(double x, double y) {
		double lon;
		double lat;
		if (proj == null) {
			GeodesicData g = Geodesic.WGS84.Direct(lat0, lon0, Math.toDegrees(Math.atan2(x, y)), Math.hypot(x, y),
				GeodesicMask.LATITUDE | GeodesicMask.LONGITUDE);
			lon = g.lon2;
			lat = g.lat2;
		} else {
			ProjCoordinate out = proj.inverseProject(new ProjCoordinate(x, y), new ProjCoordinate());
			lon = out.x;
			lat = out.y;
		}
		while (lon - lon0 > 180d) {
			lon -= 360d;
		}
		while (lon - lon0 < -180d) {
			lon += 360d;
		}
		return new double[] { lon, lat };
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

	private static double round(double v) {
		return Math.round(v * 100d) / 100d;
	}

	private static Projection get(String kind, double lon0, double lat0) {
		String params = String.format(Locale.ROOT,
			"+proj=%s +lat_0=%s +lon_0=%s +x_0=0 +y_0=0 +ellps=WGS84 +units=m +no_defs", kind, lat0, lon0);
		return CACHE.get().computeIfAbsent(params, p -> CRS.createFromParameters(null, p).getProjection());
	}
}
