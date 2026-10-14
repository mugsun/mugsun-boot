package com.mugsun.boot.gis;

import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Geometry;

/**
 * 库里已存的跨 180° 经线要素的空间谓词（mugsun-boot#64）。
 *
 * <p>存量要素可能直接以 {@code LINESTRING(179.9 0, -179.9 0)} 这类写法入库，平面上会被当成从 -179.9° 横跨到 179.9°
 * 的几何：与 0° 附近的范围误相交，在 180° 附近反而查不到。这里不改存储，在查询时识别并换到 [0, 360) 经度判断，
 * 与 PostGIS {@code ST_ShiftLongitude} 同口径；判定用 GeoTools {@code WrappingProjectionHandler} 的「半个地球」启发式：
 * 平面包络宽于 180°，且负经度加 360 后不再宽于 180°（极冠这类真正横跨全经度的面不受影响）。
 */
public final class GisAntimeridian {

	private GisAntimeridian() {
	}

	/** 是否为存量跨 180° 要素 */
	public static boolean wraps(Geometry g) {
		if (g == null || g.isEmpty() || g.getEnvelopeInternal().getWidth() <= 180d) {
			return false;
		}
		return GisGeodesic.shiftNegativeLon(g).getEnvelopeInternal().getWidth() <= 180d;
	}

	public static boolean intersects(Geometry feature, Geometry other) {
		if (!wraps(feature)) {
			return feature.intersects(other);
		}
		Geometry s = GisGeodesic.shiftNegativeLon(feature);
		return s.intersects(other) || s.intersects(shift(other, 360d));
	}

	public static boolean contains(Geometry feature, Geometry other) {
		if (!wraps(feature)) {
			return feature.contains(other);
		}
		Geometry s = GisGeodesic.shiftNegativeLon(feature);
		return s.contains(other) || s.contains(shift(other, 360d));
	}

	private static Geometry shift(Geometry g, double dx) {
		Geometry copy = g.copy();
		copy.apply((CoordinateFilter) c -> c.setX(c.x + dx));
		copy.geometryChanged();
		return copy;
	}
}
