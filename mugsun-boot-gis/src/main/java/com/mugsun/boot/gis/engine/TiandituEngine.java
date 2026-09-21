package com.mugsun.boot.gis.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.gis.GisConstants;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 天地图 DataServer 瓦片 + 国家平台地理编码 / POI；出入 WGS84。
 */
@Component
public class TiandituEngine implements GisMapEngine {

	private final GisUpstreamHttp http;

	public TiandituEngine(GisUpstreamHttp http) {
		this.http = http;
	}

	@Override
	public String code() {
		return GisConstants.PROVIDER_TIANDITU;
	}

	@Override
	public String tileUri(String layer, int z, int x, int y, String key) {
		String t = switch (GisGeoHits.normalizeLayer(layer)) {
			case "img" -> "img_w";
			case "cia" -> "cia_w";
			case "cva" -> "cva_w";
			default -> "vec_w";
		};
		int sub = Math.floorMod(x + y, 8);
		return "https://t" + (sub % 8) + ".tianditu.gov.cn/DataServer?T=" + t
			+ "&x=" + x + "&y=" + y + "&l=" + z + "&tk=" + key;
	}

	@Override
	public List<Map<String, Object>> search(String q, Double wgsLon, Double wgsLat, String key) throws Exception {
		List<Map<String, Object>> out = new ArrayList<>();
		GisGeoHits.merge(out, searchPois(q, wgsLon, wgsLat, key));
		GisGeoHits.merge(out, geocodeForward(q, key));
		return GisGeoHits.cap(out, 12);
	}

	@Override
	public Map<String, Object> reverse(double wgsLon, double wgsLat, String key) throws Exception {
		String ds = URLEncoder.encode(
			"{\"lon\":" + wgsLon + ",\"lat\":" + wgsLat + ",\"ver\":1}", StandardCharsets.UTF_8);
		JsonNode root = get("https://api.tianditu.gov.cn/geocoder?postStr=" + ds
			+ "&type=geocode&tk=" + key);
		JsonNode result = root.path("result");
		JsonNode comp = result.path("addressComponent");
		return GisGeoHits.reverse(wgsLon, wgsLat,
			GisGeoHits.firstNonBlank(GisGeoHits.text(result, "formatted_address"), GisGeoHits.text(result, "address")),
			comp.isObject() ? GisGeoHits.text(comp, "province") : "",
			comp.isObject() ? GisGeoHits.text(comp, "city") : "",
			comp.isObject() ? GisGeoHits.text(comp, "county") : "",
			comp.isObject() ? GisGeoHits.text(comp, "poi") : "");
	}

	@Override
	public boolean allowedTileHost(String host) {
		return host != null && host.toLowerCase(Locale.ROOT).endsWith("tianditu.gov.cn");
	}

	private List<Map<String, Object>> geocodeForward(String keyword, String key) throws Exception {
		String ds = URLEncoder.encode("{\"keyWord\":\"" + GisGeoHits.escapeJson(keyword) + "\"}",
			StandardCharsets.UTF_8);
		JsonNode root = get("https://api.tianditu.gov.cn/geocoder?ds=" + ds + "&tk=" + key);
		JsonNode loc = root.path("location");
		List<Map<String, Object>> list = new ArrayList<>();
		if (loc.has("lon") && loc.has("lat")) {
			list.add(GisGeoHits.poi(keyword, "", loc.path("lon").asDouble(), loc.path("lat").asDouble(), "geocode"));
		}
		return list;
	}

	private List<Map<String, Object>> searchPois(String keyword, Double lon, Double lat, String key)
		throws Exception {
		Map<String, Object> post = new LinkedHashMap<>();
		post.put("keyWord", keyword);
		post.put("queryType", "1");
		post.put("start", "0");
		post.put("count", "10");
		post.put("show", "2");
		if (lon != null && lat != null) {
			post.put("level", "12");
			double d = 0.35;
			post.put("mapBound", (lon - d) + "," + (lat - d) + "," + (lon + d) + "," + (lat + d));
		} else {
			post.put("level", "5");
			post.put("mapBound", "73,18,135,54");
		}
		String postStr = URLEncoder.encode(http.writeJson(post), StandardCharsets.UTF_8);
		JsonNode root = get("https://api.tianditu.gov.cn/v2/search?postStr=" + postStr
			+ "&type=query&tk=" + key);
		List<Map<String, Object>> list = new ArrayList<>();
		JsonNode pois = root.path("pois");
		if (pois.isArray()) {
			for (JsonNode n : pois) {
				double[] ll = GisGeoHits.parseLonLat(n.path("lonlat").asText(""));
				if (ll == null) {
					continue;
				}
				list.add(GisGeoHits.poi(GisGeoHits.text(n, "name"), GisGeoHits.text(n, "address"),
					ll[0], ll[1], "poi"));
			}
		}
		JsonNode area = root.path("area");
		if (area.isObject()) {
			double[] ll = GisGeoHits.parseLonLat(area.path("lonlat").asText(""));
			if (ll != null) {
				list.add(GisGeoHits.poi(GisGeoHits.text(area, "name"), GisGeoHits.text(area, "address"),
					ll[0], ll[1], "area"));
			}
		}
		return list;
	}

	private JsonNode get(String url) throws Exception {
		return http.getJson(URI.create(url), this::allowedTileHost);
	}
}
