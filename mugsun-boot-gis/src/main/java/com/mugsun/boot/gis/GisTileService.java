package com.mugsun.boot.gis;

import com.mugsun.boot.gis.engine.GisMapEngine;
import com.mugsun.boot.gis.engine.GisMapEngines;
import com.mugsun.boot.gis.engine.GisUpstreamHttp;
import com.mugsun.boot.gis.entity.GisMapProvider;
import com.mugsun.boot.gis.mapper.GisMapProviderMapper;
import com.mugsun.boot.gis.tile.GisTileImages;
import com.mugsun.boot.gis.xyz.GisXyzTemplate;
import com.mugsun.boot.gis.xyz.GisXyzTileService;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Base64;

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
			.header(NOSNIFF_HEADER, NOSNIFF)
			.body(EMPTY_PNG);
	}

	static final String NOSNIFF_HEADER = "X-Content-Type-Options";
	static final String NOSNIFF = "nosniff";
	/** 厂商瓦片单张上限，与 xyz 默认一致 */
	private static final int VENDOR_TILE_MAX_BYTES = 2 * 1024 * 1024;

	/** 上游图片过类型白名单（png/jpeg/webp/gif，文件头校验）后才回给浏览器，统一带 nosniff */
	static ResponseEntity<byte[]> imageOrEmpty(int status, String contentType, byte[] body) {
		if (status != 200) {
			return emptyTile();
		}
		MediaType type = GisTileImages.accept(contentType, body);
		if (type == null) {
			return emptyTile();
		}
		return ResponseEntity.ok()
			.contentType(type)
			.header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
			.header(NOSNIFF_HEADER, NOSNIFF)
			.body(body);
	}

	private final GisMapProviderMapper providerMapper;
	private final GisMapEngines engines;
	private final GisUpstreamHttp http;
	private final GisXyzTileService xyz;

	public GisTileService(GisMapProviderMapper providerMapper, GisMapEngines engines, GisUpstreamHttp http,
						  GisXyzTileService xyz) {
		this.xyz = xyz;
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
		if (GisConstants.PROVIDER_XYZ.equals(provider)) {
			return fetchXyz(cfg, z, x, y);
		}
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
			// 厂商瓦片同样不跟随重定向、限大小
			GisUpstreamHttp.LimitedBytes resp = http.getBytesStrict(uri, engine::allowedTileHost,
				GisUpstreamHttp.BROWSER_UA, VENDOR_TILE_MAX_BYTES);
			return imageOrEmpty(resp.status(), resp.contentType(), resp.body());
		} catch (ServiceException e) {
			return emptyTile();
		} catch (Exception e) {
			return emptyTile();
		}
	}

	/** 自定义 XYZ：白名单 + DNS 校验 + 不跟随重定向 + 限大小，均在 GisXyzTileService 内完成 */
	private ResponseEntity<byte[]> fetchXyz(GisMapProvider cfg, int z, int x, int y) {
		if (!xyz.available()) {
			return emptyTile();
		}
		if (cfg != null && cfg.getEnabled() != null && cfg.getEnabled() != GisConstants.STATUS_ENABLE) {
			return emptyTile();
		}
		try {
			GisXyzTemplate t = xyz.effective(cfg);
			if (t == null) {
				return emptyTile();
			}
			GisUpstreamHttp.LimitedBytes resp = xyz.fetch(t, z, x, y, cfg == null ? null : cfg.getApiKey());
			return imageOrEmpty(resp.status(), resp.contentType(), resp.body());
		} catch (Exception e) {
			return emptyTile();
		}
	}
}
