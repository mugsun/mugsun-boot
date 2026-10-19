package com.mugsun.boot.gis.xyz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.gis.GisConstants;
import com.mugsun.boot.gis.engine.GisUpstreamHttp;
import com.mugsun.boot.gis.entity.GisMapProvider;
import com.mugsun.core.tool.exception.ServiceException;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 自定义 XYZ 底图：解析租户配置（extra_json）或部署默认预设，按 SSRF 防线取瓦片并缓存。
 */
public class GisXyzTileService {

	private final GisXyzProperties props;
	private final GisUpstreamHttp http;
	private final ObjectMapper objectMapper;
	private final GisXyzTileCache cache;
	private final GisXyzGuard.Resolver resolver;

	public GisXyzTileService(GisXyzProperties props, GisUpstreamHttp http, ObjectMapper objectMapper,
							 GisXyzGuard.Resolver resolver) {
		this.props = props;
		this.http = http;
		this.objectMapper = objectMapper;
		this.resolver = resolver;
		this.cache = new GisXyzTileCache(props.getCacheMaxBytes(), props.getCacheTtlSeconds());
	}

	public GisXyzProperties props() {
		return props;
	}

	/** 白名单为空即整体禁用 xyz 底图 */
	public boolean available() {
		return props.getAllowedHosts().stream().anyMatch(h -> h != null && !h.isBlank());
	}

	/** 租户行优先；没有行时走部署默认预设；都没有返回 null */
	public GisXyzTemplate effective(GisMapProvider row) {
		if (row != null && row.getExtraJson() != null && !row.getExtraJson().isBlank()) {
			return parse(row.getExtraJson());
		}
		if (row == null) {
			return GisXyzPresets.get(props.getDefaultPreset());
		}
		return null;
	}

	/** 管理端提交的 extra_json → 校验后的规范化 JSON（入库用） */
	public String normalize(String extraJson) {
		GisXyzTemplate t = GisXyzGuard.checkTemplate(parse(extraJson), props.getAllowedHosts());
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("url", t.url());
		out.put("subdomains", t.subdomains());
		out.put("attribution", t.attribution());
		out.put("maxZoom", t.maxZoom());
		try {
			return objectMapper.writeValueAsString(out);
		} catch (Exception e) {
			throw new ServiceException(GisXyzGuard.MSG_TEMPLATE);
		}
	}

	public GisXyzTemplate parse(String extraJson) {
		if (extraJson == null || extraJson.isBlank()) {
			throw new ServiceException(GisXyzGuard.MSG_TEMPLATE);
		}
		JsonNode n;
		try {
			n = objectMapper.readTree(extraJson);
		} catch (Exception e) {
			throw new ServiceException(GisXyzGuard.MSG_TEMPLATE);
		}
		String preset = n.path("preset").asText("");
		if (!preset.isBlank() && !n.hasNonNull("url")) {
			GisXyzTemplate p = GisXyzPresets.get(preset);
			if (p == null) {
				throw new ServiceException(GisXyzGuard.MSG_TEMPLATE);
			}
			return p;
		}
		List<String> subs = new ArrayList<>();
		JsonNode s = n.path("subdomains");
		if (s.isArray()) {
			s.forEach(v -> subs.add(v.asText("")));
		} else if (s.isTextual() && !s.asText().isBlank()) {
			for (String part : s.asText().split(",")) {
				subs.add(part.trim());
			}
		}
		return new GisXyzTemplate(n.path("url").asText(""), subs, n.path("attribution").asText(""),
			n.path("maxZoom").asInt(19));
	}

	/**
	 * 取一张瓦片。每次都按当前白名单重新校验模板与地址，再做 DNS 解析校验，然后不跟随重定向地拉取。
	 */
	public GisUpstreamHttp.LimitedBytes fetch(GisXyzTemplate raw, int z, int x, int y, String key) throws Exception {
		GisXyzTemplate t = GisXyzGuard.checkTemplate(raw, props.getAllowedHosts());
		long max = 1L << z;
		if (z > t.maxZoom() || x < 0 || y < 0 || x >= max || y >= max) {
			throw new ServiceException(GisConstants.MSG_MVT_ZXY);
		}
		URI uri = GisXyzGuard.checkUri(t.render(z, x, y, key), props.getAllowedHosts());
		String cacheKey = uri.toString();
		long now = System.currentTimeMillis();
		GisXyzTileCache.Entry hit = cache.get(cacheKey, now);
		if (hit != null) {
			return new GisUpstreamHttp.LimitedBytes(200, hit.contentType(), hit.body());
		}
		GisXyzGuard.checkResolved(uri.getHost(), resolver, props.netPolicy());
		GisUpstreamHttp.LimitedBytes resp = http.getBytesStrict(uri,
			h -> GisXyzGuard.hostAllowed(h, props.getAllowedHosts()) && !GisXyzGuard.isIpLiteral(h),
			props.getUserAgent(), props.getMaxTileBytes());
		String ctype = resp.contentType() == null ? "" : resp.contentType().toLowerCase(Locale.ROOT);
		if (resp.status() == 200 && ctype.startsWith("image/")) {
			cache.put(cacheKey, resp.body(), resp.contentType(), now);
		}
		return resp;
	}
}
