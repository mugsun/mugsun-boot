package com.mugsun.boot.gis;

import com.mugsun.boot.common.module.ModuleInsightPort;
import com.mugsun.boot.gis.entity.GisLayer;
import com.mugsun.boot.gis.mapper.GisLayerMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把图层库内实数交给 AI 编织。不读 data_json，避免把大图层整份拉进解读请求。
 */
@Component
public class GisInsightAdapter implements ModuleInsightPort {

	private final GisModuleService moduleService;
	private final GisLayerMapper layerMapper;

	public GisInsightAdapter(GisModuleService moduleService, GisLayerMapper layerMapper) {
		this.moduleService = moduleService;
		this.layerMapper = layerMapper;
	}

	@Override
	public String module() {
		return "gis";
	}

	@Override
	public Map<String, Object> snapshot(Map<String, Object> request) {
		moduleService.requireEnabled();
		long layerCount = layerMapper.selectCountByQuery(QueryWrapper.create());
		List<GisLayer> layers = layerMapper.selectListByQuery(QueryWrapper.create()
			.select("id", "name", "kind", "feature_count")
			.orderBy("id", true));
		long featureCount = 0;
		List<Map<String, Object>> rows = new ArrayList<>();
		for (GisLayer layer : layers) {
			int n = layer.getFeatureCount() == null ? 0 : layer.getFeatureCount();
			featureCount += n;
			if (rows.size() < 20) {
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("id", layer.getId());
				row.put("name", layer.getName());
				row.put("kind", layer.getKind());
				row.put("featureCount", n);
				rows.add(row);
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("source", "gis_layer");
		out.put("layerCount", layerCount);
		out.put("featureCount", featureCount);
		out.put("layers", rows);
		return out;
	}
}
