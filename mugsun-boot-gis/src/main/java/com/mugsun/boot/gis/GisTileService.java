package com.mugsun.boot.gis;

import com.mugsun.boot.gis.engine.GisMapEngine;
import com.mugsun.boot.gis.engine.GisMapEngines;
import com.mugsun.boot.gis.engine.GisUpstreamHttp;
import com.mugsun.boot.gis.entity.GisMapProvider;
import com.mugsun.boot.gis.mapper.GisMapProviderMapper;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Locale;

/**
 * 瓦片反代门面：密钥留在服务端；上游失败返回空 PNG，避免 OL 把 JSON 当瓦片。
 */
@Service
public class GisTileService {

	/** 1×1 透明 PNG：密钥缺失/上游失败时仍返回图片，避免 OL/Cesium 把 JSON 当瓦片解码后停渲 */
	private static final byte[] EMPTY_PNG = Base64.getDecoder().decode(
		"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=");

	private static ResponseEntity<byte[]> emptyTile() {
		return ResponseEntity.ok()
			.contentType(MediaType.IMAGE_PNG)
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.body(EMPTY_PNG);
	}

	private final GisMapProviderMapper providerMapper;
	private final GisMapEngines engines;
	private final GisUpstreamHttp http;

	public GisTileService(GisMapProviderMapper providerMapper, GisMapEngines engines, GisUpstreamHttp http) {
		this.providerMapper = providerMapper;
		this.engines = engines;
		this.http = http;
	}

	public ResponseEntity<byte[]> fetch(String provider, String layer, int z, int x, int y) {
		if (!GisConstants.PROVIDERS.contains(provider)) {
			throw new ServiceException(GisConstants.MSG_PROVIDER_UNKNOWN);
		}
		if (z < GisConstants.TILE_MIN_Z || z > GisConstants.TILE_MAX_Z) {
			throw new ServiceException("缩放级别超出范围");
		}
		GisMapProvider cfg = providerMapper.selectOneByQuery(QueryWrapper.create().eq("provider", provider));
		if (cfg == null || cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
			return emptyTile();
		}
		if (cfg.getEnabled() != null && cfg.getEnabled() != GisConstants.STATUS_ENABLE) {
			return emptyTile();
		}
		GisMapEngine engine = engines.require(provider);
		try {
			URI uri = URI.create(engine.tileUri(layer, z, x, y, cfg.getApiKey()));
			String host = uri.getHost();
			if (!engine.allowedTileHost(host)) {
				return emptyTile();
			}
			HttpResponse<byte[]> resp = http.getBytes(uri, engine::allowedTileHost);
			if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
				return emptyTile();
			}
			String ctype = resp.headers().firstValue("Content-Type").orElse(MediaType.IMAGE_PNG_VALUE);
			String parsed = ctype.contains("/") ? ctype.split(";")[0].trim() : MediaType.IMAGE_PNG_VALUE;
			if (!parsed.toLowerCase(Locale.ROOT).startsWith("image/")) {
				return emptyTile();
			}
			if ("image/jpg".equalsIgnoreCase(parsed)) {
				parsed = MediaType.IMAGE_JPEG_VALUE;
			}
			return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(parsed))
				.header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
				.body(resp.body());
		} catch (ServiceException e) {
			return emptyTile();
		} catch (Exception e) {
			return emptyTile();
		}
	}
}
