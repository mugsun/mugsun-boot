package com.mugsun.boot.gis;

import com.mugsun.core.tool.exception.ServiceException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 栅格服务图层契约：XYZ / WMS。URL 格式对即可叠加，不绑某一家底图。
 */
public final class GisRasterSpec {

	private GisRasterSpec() {
	}

	public static boolean isRaster(String kind) {
		return GisConstants.KIND_XYZ.equals(kind) || GisConstants.KIND_WMS.equals(kind);
	}

	public static Map<String, Object> normalize(String kind, Object payload) {
		Map<String, Object> src = asMap(payload);
		String url = str(src.get("url"));
		if (url.isEmpty() && src.get("type") == null && payload instanceof String s) {
			url = s.trim();
		}
		if (!url.startsWith("http://") && !url.startsWith("https://")) {
			throw new ServiceException(GisConstants.MSG_RASTER_URL);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		if (GisConstants.KIND_XYZ.equals(kind)) {
			String low = url.toLowerCase(Locale.ROOT);
			if (!low.contains("{z}") || !low.contains("{x}") || !low.contains("{y}")) {
				throw new ServiceException(GisConstants.MSG_RASTER_URL);
			}
			out.put("type", "XYZ");
			out.put("url", url);
			return out;
		}
		String layers = str(src.get("layers"));
		if (layers.isEmpty()) {
			throw new ServiceException(GisConstants.MSG_RASTER_URL);
		}
		out.put("type", "WMS");
		out.put("url", url);
		out.put("layers", layers);
		String format = str(src.get("format"));
		out.put("format", format.isEmpty() ? "image/png" : format);
		return out;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object payload) {
		if (payload instanceof Map<?, ?> map) {
			Map<String, Object> out = new LinkedHashMap<>();
			map.forEach((k, v) -> out.put(String.valueOf(k), v));
			return out;
		}
		return new LinkedHashMap<>();
	}

	private static String str(Object v) {
		return v == null ? "" : String.valueOf(v).trim();
	}
}
