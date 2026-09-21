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
 * 高德 XYZ 瓦片 + Web 服务 place/geocode/regeo；对外 WGS84，上游 GCJ-02。
 */
@Component
public class AmapEngine implements GisMapEngine {

	private final GisUpstreamHttp http;

	public AmapEngine(GisUpstreamHttp http) {
		this.http = http;
	}

	@Override
	public String code() {
		return GisConstants.PROVIDER_AMAP;
	}

	@Override
	public String tileUri(String layer, int z, int x, int y, String key) {
		String layerKey = GisGeoHits.normalizeLayer(layer);
		int s = (Math.floorMod(x + y, 8) % 4) + 1;
		if ("img".equals(layerKey)) {
			return "https://webst0" + s + ".is.autonavi.com/appmaptile?style=6&x=" + x + "&y=" + y
				+ "&z=" + z + "&key=" + key;
		}
		if ("cia".equals(layerKey) || "cva".equals(layerKey)) {
			return "https://webst0" + s + ".is.autonavi.com/appmaptile?style=8&x=" + x + "&y=" + y
				+ "&z=" + z + "&key=" + key;
		}
		return "https://webrd0" + s + ".is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x="
			+ x + "&y=" + y + "&z=" + z + "&key=" + key;
	}

	@Override
	public List<Map<String, Object>> search(String q, Double wgsLon, Double wgsLat, String key) throws Exception {
		List<Map<String, Object>> out = new ArrayList<>();
		GisGeoHits.merge(out, amapPois(q, wgsLon, wgsLat, key));
		GisGeoHits.merge(out, amapGeocode(q, key));
		return GisGeoHits.cap(out, 12);
	}

	@Override
	public Map<String, Object> reverse(double wgsLon, double wgsLat, String key) throws Exception {
		double[] gcj = GisChinaCrs.toGcj02(wgsLon, wgsLat);
		String loc = GisGeoHits.fmt(gcj[0]) + "," + GisGeoHits.fmt(gcj[1]);
		JsonNode root = get("https://restapi.amap.com/v3/geocode/regeo?location=" + loc
			+ "&extensions=base&key=" + key);
		assertOk(root);
		JsonNode regeo = root.path("regeocode");
		JsonNode comp = regeo.path("addressComponent");
		return GisGeoHits.reverse(wgsLon, wgsLat,
			GisGeoHits.looseText(regeo, "formatted_address"),
			GisGeoHits.looseText(comp, "province"),
			GisGeoHits.firstNonBlank(GisGeoHits.looseText(comp, "city"), GisGeoHits.looseText(comp, "province")),
			GisGeoHits.looseText(comp, "district"),
			GisGeoHits.firstNonBlank(GisGeoHits.looseText(comp, "township"), GisGeoHits.looseText(comp, "street")));
	}

	@Override
	public boolean allowedTileHost(String host) {
		if (host == null) {
			return false;
		}
		String h = host.toLowerCase(Locale.ROOT);
		return h.endsWith("autonavi.com") || h.endsWith("amap.com");
	}

	private List<Map<String, Object>> amapPois(String keyword, Double wgsLon, Double wgsLat, String key)
		throws Exception {
		StringBuilder url = new StringBuilder("https://restapi.amap.com/v3/place/text?keywords=")
			.append(URLEncoder.encode(keyword, StandardCharsets.UTF_8))
			.append("&offset=10&page=1&extensions=base&key=").append(key);
		if (wgsLon != null && wgsLat != null) {
			double[] gcj = GisChinaCrs.toGcj02(wgsLon, wgsLat);
			url.append("&location=").append(GisGeoHits.fmt(gcj[0])).append(",").append(GisGeoHits.fmt(gcj[1]))
				.append("&sortrule=distance");
		}
		JsonNode root = get(url.toString());
		assertOk(root);
		List<Map<String, Object>> list = new ArrayList<>();
		JsonNode pois = root.path("pois");
		if (pois.isArray()) {
			for (JsonNode n : pois) {
				double[] wgs = locationToWgs(GisGeoHits.looseText(n, "location"));
				if (wgs == null) {
					continue;
				}
				list.add(GisGeoHits.poi(GisGeoHits.looseText(n, "name"), GisGeoHits.looseText(n, "address"),
					wgs[0], wgs[1], "poi"));
			}
		}
		return list;
	}

	private List<Map<String, Object>> amapGeocode(String keyword, String key) throws Exception {
		JsonNode root = get("https://restapi.amap.com/v3/geocode/geo?address="
			+ URLEncoder.encode(keyword, StandardCharsets.UTF_8) + "&key=" + key);
		assertOk(root);
		List<Map<String, Object>> list = new ArrayList<>();
		JsonNode rows = root.path("geocodes");
		if (rows.isArray() && rows.size() > 0) {
			JsonNode n = rows.get(0);
			double[] wgs = locationToWgs(GisGeoHits.looseText(n, "location"));
			if (wgs != null) {
				list.add(GisGeoHits.poi(
					GisGeoHits.firstNonBlank(GisGeoHits.looseText(n, "formatted_address"), keyword),
					GisGeoHits.looseText(n, "formatted_address"), wgs[0], wgs[1], "geocode"));
			}
		}
		return list;
	}

	private JsonNode get(String url) throws Exception {
		return http.getJson(URI.create(url), this::allowedTileHost);
	}

	private static void assertOk(JsonNode root) {
		if (!"1".equals(root.path("status").asText())) {
			throw new ServiceException(GisConstants.MSG_SEARCH_UPSTREAM);
		}
	}

	private static double[] locationToWgs(String location) {
		double[] gcj = GisGeoHits.parseLonLat(location);
		if (gcj == null) {
			return null;
		}
		return GisChinaCrs.toWgs84(gcj[0], gcj[1]);
	}
}
