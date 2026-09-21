package com.mugsun.boot.gis.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.gis.GisConstants;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Google Map Tiles session + Geocoding API；WGS84。瓦片 Key 未开地理编码时统一报检索不可用。
 */
@Component
public class GoogleEngine implements GisMapEngine {

	private final GisUpstreamHttp http;
	private final Map<String, Session> sessions = new ConcurrentHashMap<>();

	public GoogleEngine(GisUpstreamHttp http) {
		this.http = http;
	}

	@Override
	public String code() {
		return GisConstants.PROVIDER_GOOGLE;
	}

	@Override
	public String tileUri(String layer, int z, int x, int y, String key) throws Exception {
		String mapType = "img".equals(GisGeoHits.normalizeLayer(layer)) ? "satellite" : "roadmap";
		String session = session(key, mapType);
		return "https://tile.googleapis.com/v1/2dtiles/" + z + "/" + x + "/" + y
			+ "?session=" + session + "&key=" + key;
	}

	@Override
	public List<Map<String, Object>> search(String q, Double wgsLon, Double wgsLat, String key) throws Exception {
		JsonNode root = get("https://maps.googleapis.com/maps/api/geocode/json?address="
			+ URLEncoder.encode(q, StandardCharsets.UTF_8) + "&key=" + key);
		assertGeocode(root);
		List<Map<String, Object>> list = new ArrayList<>();
		JsonNode rows = root.path("results");
		if (rows.isArray()) {
			int n = Math.min(rows.size(), 10);
			for (int i = 0; i < n; i++) {
				JsonNode item = rows.get(i);
				double[] wgs = geoLocation(item.path("geometry").path("location"));
				if (wgs == null) {
					continue;
				}
				String name = GisGeoHits.firstNonBlank(GisGeoHits.text(item, "formatted_address"), q);
				list.add(GisGeoHits.poi(name, GisGeoHits.text(item, "formatted_address"), wgs[0], wgs[1], "geocode"));
			}
		}
		return GisGeoHits.cap(list, 12);
	}

	@Override
	public Map<String, Object> reverse(double wgsLon, double wgsLat, String key) throws Exception {
		JsonNode root = get("https://maps.googleapis.com/maps/api/geocode/json?latlng="
			+ GisGeoHits.fmt(wgsLat) + "," + GisGeoHits.fmt(wgsLon) + "&key=" + key);
		assertGeocode(root);
		JsonNode first = root.path("results").path(0);
		String[] parts = googleComponents(first.path("address_components"));
		return GisGeoHits.reverse(wgsLon, wgsLat,
			GisGeoHits.text(first, "formatted_address"),
			parts[0], parts[1], parts[2], parts[3]);
	}

	@Override
	public boolean allowedTileHost(String host) {
		return host != null && host.toLowerCase(Locale.ROOT).endsWith("googleapis.com");
	}

	private String session(String key, String mapType) throws Exception {
		String cacheKey = key.hashCode() + ":" + mapType;
		Session cached = sessions.get(cacheKey);
		if (cached != null && cached.expireAt > System.currentTimeMillis() + 60_000) {
			return cached.token;
		}
		String body = "{\"mapType\":\"" + mapType + "\",\"language\":\"zh-CN\",\"region\":\"US\"}";
		JsonNode n = http.postJson(
			URI.create("https://tile.googleapis.com/v1/createSession?key=" + key),
			body, this::allowedTileHost);
		String token = n.path("session").asText(null);
		if (token == null || token.isBlank()) {
			throw new ServiceException("Google 会话创建失败");
		}
		long expire = System.currentTimeMillis() + Duration.ofDays(1).toMillis();
		sessions.put(cacheKey, new Session(token, expire));
		return token;
	}

	private JsonNode get(String url) throws Exception {
		return http.getJson(URI.create(url), this::allowedTileHost);
	}

	private static void assertGeocode(JsonNode root) {
		String status = root.path("status").asText("");
		if ("OK".equals(status) || "ZERO_RESULTS".equals(status)) {
			return;
		}
		throw new ServiceException(GisConstants.MSG_SEARCH_UPSTREAM);
	}

	private static double[] geoLocation(JsonNode loc) {
		if (loc == null || !loc.isObject() || !loc.has("lng") || !loc.has("lat")) {
			return null;
		}
		return new double[] { loc.path("lng").asDouble(), loc.path("lat").asDouble() };
	}

	private static String[] googleComponents(JsonNode comps) {
		String province = "";
		String city = "";
		String county = "";
		String poi = "";
		if (comps.isArray()) {
			for (JsonNode c : comps) {
				String types = c.path("types").toString();
				String name = GisGeoHits.text(c, "long_name");
				if (types.contains("administrative_area_level_1") && province.isBlank()) {
					province = name;
				} else if ((types.contains("\"locality\"") || types.contains("administrative_area_level_2"))
					&& city.isBlank()) {
					city = name;
				} else if ((types.contains("sublocality") || types.contains("administrative_area_level_3"))
					&& county.isBlank()) {
					county = name;
				} else if (types.contains("point_of_interest") && poi.isBlank()) {
					poi = name;
				}
			}
		}
		return new String[] { province, city, county, poi };
	}

	private record Session(String token, long expireAt) {
	}
}
