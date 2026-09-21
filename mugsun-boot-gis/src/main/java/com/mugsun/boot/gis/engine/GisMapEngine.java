package com.mugsun.boot.gis.engine;

import java.util.List;
import java.util.Map;

/**
 * 一家底图供应商一个引擎：瓦片、检索、逆地理都只打这一家的官方接口。
 */
public interface GisMapEngine {

	String code();

	String tileUri(String layer, int z, int x, int y, String key) throws Exception;

	List<Map<String, Object>> search(String q, Double wgsLon, Double wgsLat, String key) throws Exception;

	Map<String, Object> reverse(double wgsLon, double wgsLat, String key) throws Exception;

	boolean allowedTileHost(String host);
}
