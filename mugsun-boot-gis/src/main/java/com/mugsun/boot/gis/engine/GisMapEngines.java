package com.mugsun.boot.gis.engine;

import com.mugsun.boot.gis.GisConstants;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按供应商 code 取引擎；没有对应实现即未知供应商，不跨家兜底。
 */
@Component
public class GisMapEngines {

	private final Map<String, GisMapEngine> byCode;

	public GisMapEngines(List<GisMapEngine> engines) {
		Map<String, GisMapEngine> map = new LinkedHashMap<>();
		for (GisMapEngine engine : engines) {
			map.put(engine.code(), engine);
		}
		this.byCode = Map.copyOf(map);
	}

	public GisMapEngine require(String code) {
		GisMapEngine engine = byCode.get(code);
		if (engine == null) {
			throw new ServiceException(GisConstants.MSG_PROVIDER_UNKNOWN);
		}
		return engine;
	}

	public Set<String> codes() {
		return byCode.keySet();
	}
}
