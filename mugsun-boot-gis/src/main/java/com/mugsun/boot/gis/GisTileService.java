package com.mugsun.boot.gis;

import com.mugsun.boot.gis.engine.GisMapEngine;
import com.mugsun.boot.gis.engine.GisMapEngines;
import com.mugsun.boot.gis.engine.GisUpstreamHttp;
import com.mugsun.boot.gis.entity.GisMapProvider;
import com.mugsun.boot.gis.mapper.GisMapProviderMapper;
import com.mugsun.boot.gis.tile.GisTileFailure;
import com.mugsun.boot.gis.tile.GisTileFailureLog;
import com.mugsun.boot.gis.tile.GisTileImages;
import com.mugsun.boot.gis.xyz.GisXyzTemplate;
import com.mugsun.boot.gis.xyz.GisXyzTileService;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.net.URI;

/**
 * 瓦片反代门面：密钥留在服务端。
 * 失败不再伪装成 200 透明图：按原因回 502 / 504 / 403 / 404，带 X-Mugsun-Tile-Error 原因头和 no-store，
 * 上游类失败按 host + 原因限频打 WARN（不记 URL 和密钥）。前端据此识别瓦片失败。
 */
@Service
public class GisTileService {

	static final String NOSNIFF_HEADER = "X-Content-Type-Options";
	static final String NOSNIFF = "nosniff";
	/** 厂商瓦片单张上限，与 xyz 默认一致 */
	private static final int VENDOR_TILE_MAX_BYTES = 2 * 1024 * 1024;

	/** 失败响应：空正文、no-store、nosniff、原因头 */
	static ResponseEntity<byte[]> failure(GisTileFailure reason) {
		return ResponseEntity.status(reason.status())
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.header(NOSNIFF_HEADER, NOSNIFF)
			.header(GisTileFailure.HEADER, reason.code())
			.build();
	}

	/** 上游图片过类型白名单（png/jpeg/webp/gif，文件头校验）后才回给浏览器，统一带 nosniff；否则给出失败原因 */
	static GisTileFailure classify(int status, String contentType, byte[] body) {
		if (status != 200) {
			return GisTileFailure.UPSTREAM_STATUS;
		}
		return GisTileImages.accept(contentType, body) == null ? GisTileFailure.BAD_CONTENT_TYPE : null;
	}

	static ResponseEntity<byte[]> image(String contentType, byte[] body) {
		return ResponseEntity.ok()
			.contentType(GisTileImages.accept(contentType, body))
			.header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
			.header(NOSNIFF_HEADER, NOSNIFF)
			.body(body);
	}

	private final GisMapProviderMapper providerMapper;
	private final GisMapEngines engines;
	private final GisUpstreamHttp http;
	private final GisXyzTileService xyz;
	private GisTileFailureLog failureLog = new GisTileFailureLog();

	public GisTileService(GisMapProviderMapper providerMapper, GisMapEngines engines, GisUpstreamHttp http,
						  GisXyzTileService xyz) {
		this.xyz = xyz;
		this.providerMapper = providerMapper;
		this.engines = engines;
		this.http = http;
	}

	/** 单测替换日志输出 */
	void setFailureLog(GisTileFailureLog failureLog) {
		this.failureLog = failureLog;
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
			return failure(GisTileFailure.NOT_CONFIGURED);
		}
		if (cfg.getEnabled() != null && cfg.getEnabled() != GisConstants.STATUS_ENABLE) {
			return failure(GisTileFailure.DISABLED);
		}
		GisMapEngine engine = engines.require(provider);
		String host = null;
		try {
			URI uri = URI.create(engine.tileUri(layer, z, x, y, cfg.getApiKey()));
			host = uri.getHost();
			if (!engine.allowedTileHost(host)) {
				return fail(provider, host, GisTileFailure.HOST_NOT_ALLOWED, 0, z);
			}
			// 厂商瓦片同样不跟随重定向、限大小
			GisUpstreamHttp.LimitedBytes resp = http.getBytesStrict(uri, engine::allowedTileHost,
				GisUpstreamHttp.BROWSER_UA, VENDOR_TILE_MAX_BYTES);
			return respond(provider, host, z, resp);
		} catch (Exception e) {
			return fail(provider, host, GisTileFailure.of(e), 0, z);
		}
	}

	/** 自定义 XYZ：白名单 + DNS 校验 + 不跟随重定向 + 限大小，均在 GisXyzTileService 内完成 */
	private ResponseEntity<byte[]> fetchXyz(GisMapProvider cfg, int z, int x, int y) {
		String provider = GisConstants.PROVIDER_XYZ;
		if (!xyz.available()) {
			return failure(GisTileFailure.NOT_CONFIGURED);
		}
		if (cfg != null && cfg.getEnabled() != null && cfg.getEnabled() != GisConstants.STATUS_ENABLE) {
			return failure(GisTileFailure.DISABLED);
		}
		String host = null;
		try {
			GisXyzTemplate t = xyz.effective(cfg);
			if (t == null) {
				return failure(GisTileFailure.NOT_CONFIGURED);
			}
			host = t.host();
			GisUpstreamHttp.LimitedBytes resp = xyz.fetch(t, z, x, y, cfg == null ? null : cfg.getApiKey());
			return respond(provider, host, z, resp);
		} catch (Exception e) {
			return fail(provider, host, GisTileFailure.of(e), 0, z);
		}
	}

	private ResponseEntity<byte[]> respond(String provider, String host, int z, GisUpstreamHttp.LimitedBytes resp) {
		GisTileFailure reason = classify(resp.status(), resp.contentType(), resp.body());
		if (reason != null) {
			return fail(provider, host, reason, resp.status(), z);
		}
		return image(resp.contentType(), resp.body());
	}

	private ResponseEntity<byte[]> fail(String provider, String host, GisTileFailure reason, int upstreamStatus, int z) {
		failureLog.record(provider, host, reason, upstreamStatus, z);
		return failure(reason);
	}
}
