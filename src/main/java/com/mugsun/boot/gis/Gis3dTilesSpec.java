package com.mugsun.boot.gis;

import com.mugsun.core.tool.exception.ServiceException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 三维切片图层契约（3D Tiles）：倾斜摄影、实景网格、建筑模型都走这一种。
 * <p>数据源两种：{@code hosted:<code>} 指向随包发布的示例切片，由 {@code /system/gis/tileset} 下发；
 * {@code http(s)://.../tileset.json} 指向用户自己的切片服务。
 */
public final class Gis3dTilesSpec {

	/** 内置切片的 url 前缀，后面跟 {@link GisConstants#TILESETS} 里的 code */
	public static final String HOSTED_PREFIX = "hosted:";

	private Gis3dTilesSpec() {
	}

	public static boolean is3dTiles(String kind) {
		return GisConstants.KIND_3DTILES.equals(kind);
	}

	/**
	 * 规格化图层 payload。
	 *
	 * @param payload 前端传的对象或纯字符串 url
	 * @return {@code {type, url, hosted?, maximumScreenSpaceError, heightOffset}}
	 */
	public static Map<String, Object> normalize(Object payload) {
		Map<String, Object> src = asMap(payload);
		String url = str(src.get("url"));
		if (url.isEmpty() && payload instanceof String s) {
			url = s.trim();
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("type", "3DTILES");
		if (url.startsWith(HOSTED_PREFIX)) {
			String code = url.substring(HOSTED_PREFIX.length()).trim();
			if (!GisConstants.TILESETS.contains(code)) {
				throw new ServiceException(GisConstants.MSG_TILESET_MISSING);
			}
			out.put("url", HOSTED_PREFIX + code);
			out.put("hosted", code);
		} else {
			String low = url.toLowerCase(Locale.ROOT);
			if (!low.startsWith("http://") && !low.startsWith("https://")) {
				throw new ServiceException(GisConstants.MSG_TILESET_URL);
			}
			// tileset.json 是 3D Tiles 的入口清单，指到别处 Cesium 起不来，早报错好过前端白屏
			if (!low.contains("tileset.json") && !low.contains(".json")) {
				throw new ServiceException(GisConstants.MSG_TILESET_URL);
			}
			out.put("url", url);
		}
		out.put("maximumScreenSpaceError", sse(src.get("maximumScreenSpaceError")));
		out.put("heightOffset", offset(src.get("heightOffset")));
		return out;
	}

	/** 屏幕空间误差：越小越清晰也越吃流量，限定在 1～64 */
	private static double sse(Object v) {
		double value = num(v, 16d);
		return Math.min(64d, Math.max(1d, value));
	}

	/** 高程偏移：切片贴地不准时整体抬降，限定在 ±5000 米 */
	private static double offset(Object v) {
		double value = num(v, 0d);
		return Math.min(5000d, Math.max(-5000d, value));
	}

	private static double num(Object v, double fallback) {
		if (v instanceof Number n) {
			return n.doubleValue();
		}
		if (v == null) {
			return fallback;
		}
		try {
			return Double.parseDouble(String.valueOf(v).trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
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
