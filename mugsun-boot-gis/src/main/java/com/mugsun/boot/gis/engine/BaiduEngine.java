package com.mugsun.boot.gis.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.gis.GisChinaCrs;
import com.mugsun.boot.gis.GisConstants;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 百度瓦片 + Web 服务检索；对外 WGS84，上游 BD-09。
 */
@Component
public class BaiduEngine implements GisMapEngine {

	private final GisUpstreamHttp http;

	public BaiduEngine(GisUpstreamHttp http) {
		this.http = http;
	}

	@Override
	public String code() {
		return GisConstants.PROVIDER_BAIDU;
	}

	@Override
	public String tileUri(String layer, int z, int x, int y, String key) {
		int s = Math.floorMod(x + y, 8) % 4;
		if ("img".equals(GisGeoHits.normalizeLayer(layer))) {
			return "https://maponline" + s + ".bdimg.com/starpic/?qt=satepc&u=x=" + x + ";y=" + y
				+ ";z=" + z + ";v=009;type=sate&fm=46&ak=" + key;
		}
		return "https://maponline" + s + ".bdimg.com/tile/?qt=vtile&x=" + x + "&y=" + y + "&z=" + z
			+ "&styles=pl&scaler=1&ak=" + key;
	}

	@Override
	public List<Map<String, Object>> search(String q, Double wgsLon, Double wgsLat, String key) throws Exception {
		List<Map<String, Object>> out = new ArrayList<>();
		GisGeoHits.merge(out, placeSearch(q, wgsLon, wgsLat, key));
		GisGeoHits.merge(out, geocode(q, key));
		return GisGeoHits.cap(out, 12);
	}

	@Override
	public Map<String, Object> reverse(double wgsLon, double wgsLat, String key) throws Exception {
		String loc = GisGeoHits.fmt(wgsLat) + "," + GisGeoHits.fmt(wgsLon);
		JsonNode root = get("https://api.map.baidu.com/reverse_geocoding/v3/?output=json&coordtype=wgs84ll&location="
			+ loc + "&ak=" + key);
		assertOk(root);
		JsonNode result = root.path("result");
		JsonNode comp = result.path("addressComponent");
		return GisGeoHits.reverse(wgsLon, wgsLat,
			GisGeoHits.text(result, "formatted_address"),
			GisGeoHits.text(comp, "province"),
			GisGeoHits.firstNonBlank(GisGeoHits.text(comp, "city"), GisGeoHits.text(comp, "province")),
			GisGeoHits.text(comp, "district"),
			GisGeoHits.firstNonBlank(GisGeoHits.text(result, "sematic_description"),
				GisGeoHits.text(comp, "street")));
	}

	@Override
	public boolean allowedTileHost(String host) {
		if (host == null) {
			return false;
		}
		String h = host.toLowerCase(Locale.ROOT);
		return h.endsWith("bdimg.com") || h.endsWith("baidu.com");
	}

	private List<Map<String, Object>> placeSearch(String keyword, Double wgsLon, Double wgsLat, String key)
		throws Exception {
		StringBuilder url = new StringBuilder("https://api.map.baidu.com/place/v2/search?query=")
			.append(URLEncoder.encode(keyword, StandardCharsets.UTF_8))
			.append("&output=json&scope=1&ak=").append(key);
		if (wgsLon != null && wgsLat != null) {
			double[] bd = GisChinaCrs.toBd09(wgsLon, wgsLat);
			url.append("&location=").append(GisGeoHits.fmt(bd[1])).append(",").append(GisGeoHits.fmt(bd[0]))
				.append("&radius=50000");
		} else {
			url.append("&region=").append(URLEncoder.encode("全国", StandardCharsets.UTF_8));
		}
		JsonNode root = get(url.toString());
		assertOk(root);
		List<Map<String, Object>> list = new ArrayList<>();
		JsonNode rows = root.path("results");
		if (rows.isArray()) {
			for (JsonNode n : rows) {
				double[] wgs = bdLocationToWgs(n.path("location"));
				if (wgs == null) {
					continue;
				}
				list.add(GisGeoHits.poi(GisGeoHits.text(n, "name"), GisGeoHits.text(n, "address"),
					wgs[0], wgs[1], "poi"));
			}
		}
		return list;
	}

	private List<Map<String, Object>> geocode(String keyword, String key) throws Exception {
		JsonNode root = get("https://api.map.baidu.com/geocoding/v3/?address="
			+ URLEncoder.encode(keyword, StandardCharsets.UTF_8) + "&output=json&ak=" + key);
		assertOk(root);
		List<Map<String, Object>> list = new ArrayList<>();
		double[] wgs = bdLocationToWgs(root.path("result").path("location"));
		if (wgs != null) {
			list.add(GisGeoHits.poi(keyword, GisGeoHits.text(root.path("result"), "level"),
				wgs[0], wgs[1], "geocode"));
		}
		return list;
	}

	private JsonNode get(String url) throws Exception {
		return http.getJson(URI.create(url), this::allowedTileHost);
	}

	private static void assertOk(JsonNode root) {
		if (root.path("status").asInt(-1) != 0) {
			throw new ServiceException(GisConstants.MSG_SEARCH_UPSTREAM);
		}
	}

	private static double[] bdLocationToWgs(JsonNode loc) {
		if (loc == null || !loc.isObject() || !loc.has("lng") || !loc.has("lat")) {
			return null;
		}
		return GisChinaCrs.bd09ToWgs84(loc.path("lng").asDouble(), loc.path("lat").asDouble());
	}
}
