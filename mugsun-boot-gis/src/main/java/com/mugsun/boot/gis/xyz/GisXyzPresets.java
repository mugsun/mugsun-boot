package com.mugsun.boot.gis.xyz;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开箱即用的免密钥瓦片预设。归属文本按各家要求书写，前端原样显示在地图右下角。
 * <p>
 * 未内置 CARTO：basemaps.cartocdn.com 现对无密钥请求返回「API KEY REQUIRED」水印瓦片，
 * 需要的部署可自行把域名加入白名单并在「底图配置」里填写地址与密钥。
 */
public final class GisXyzPresets {

	public static final String OSM = "osm";

	public static final String ATTR_OSM = "© OpenStreetMap contributors";

	private static final Map<String, GisXyzTemplate> PRESETS;

	static {
		Map<String, GisXyzTemplate> map = new LinkedHashMap<>();
		map.put(OSM, new GisXyzTemplate("https://tile.openstreetmap.org/{z}/{x}/{y}.png", List.of(), ATTR_OSM, 19));
		PRESETS = Map.copyOf(map);
	}

	private GisXyzPresets() {
	}

	public static GisXyzTemplate get(String code) {
		return code == null ? null : PRESETS.get(code);
	}

	public static Map<String, GisXyzTemplate> all() {
		Map<String, GisXyzTemplate> ordered = new LinkedHashMap<>();
		ordered.put(OSM, PRESETS.get(OSM));
		return ordered;
	}
}
