package com.mugsun.boot.gis;

import com.mugsun.boot.gis.engine.GisMapEngine;
import com.mugsun.boot.gis.engine.GisMapEngines;
import com.mugsun.boot.gis.engine.GisGeoHits;
import com.mugsun.boot.gis.entity.GisMapProvider;
import com.mugsun.boot.gis.mapper.GisMapProviderMapper;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 地名搜索与逆地理编码门面：必须指定供应商，只走该引擎，禁止跨厂商兜底。
 */
@Service
public class GisSearchService {

	private final GisMapProviderMapper providerMapper;
	private final GisMapEngines engines;

	public GisSearchService(GisMapProviderMapper providerMapper, GisMapEngines engines) {
		this.providerMapper = providerMapper;
		this.engines = engines;
	}

	public List<Map<String, Object>> search(String keyword, Double lon, Double lat, String provider) {
		String q = keyword == null ? "" : keyword.trim();
		if (q.length() < 2 || q.length() > 64) {
			throw new ServiceException(GisConstants.MSG_SEARCH_KEYWORD);
		}
		String code = requireProvider(provider);
		GisMapEngine engine = engines.require(code);
		String key = requireKey(code);
		try {
			return GisGeoHits.cap(engine.search(q, lon, lat, key), 12);
		} catch (ServiceException e) {
			throw e;
		} catch (Exception e) {
			throw new ServiceException(GisConstants.MSG_SEARCH_UPSTREAM);
		}
	}

	public Map<String, Object> reverse(double lon, double lat, String provider) {
		if (lon < -180 || lon > 180 || lat < -90 || lat > 90) {
			throw new ServiceException("坐标超出范围");
		}
		String code = requireProvider(provider);
		GisMapEngine engine = engines.require(code);
		String key = requireKey(code);
		try {
			return engine.reverse(lon, lat, key);
		} catch (ServiceException e) {
			throw e;
		} catch (Exception e) {
			throw new ServiceException(GisConstants.MSG_SEARCH_UPSTREAM);
		}
	}

	private static String requireProvider(String requested) {
		if (requested == null || requested.isBlank()) {
			throw new ServiceException(GisConstants.MSG_SEARCH_PROVIDER);
		}
		String code = requested.trim().toLowerCase(Locale.ROOT);
		if (!GisConstants.PROVIDERS.contains(code)) {
			throw new ServiceException(GisConstants.MSG_PROVIDER_UNKNOWN);
		}
		return code;
	}

	private String requireKey(String provider) {
		GisMapProvider cfg = providerMapper.selectOneByQuery(QueryWrapper.create().eq("provider", provider));
		if (cfg == null || cfg.getApiKey() == null || cfg.getApiKey().isBlank()
			|| (cfg.getEnabled() != null && cfg.getEnabled() != GisConstants.STATUS_ENABLE)) {
			throw new ServiceException(GisConstants.searchNoKey(provider));
		}
		return cfg.getApiKey();
	}
}
