package com.mugsun.boot.track;

import com.mugsun.boot.common.module.ModuleInsightPort;
import com.mugsun.boot.track.entity.TrackApp;
import com.mugsun.boot.track.mapper.TrackAppMapper;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把埋点接入应用与概览实数交给 AI 编织。概览走既有分析查询，不另造指标。
 * <p>类上 {@link TrackDS}：track_app 在埋点库，不能落到业务库。
 */
@Component
@TrackDS
public class TrackInsightAdapter implements ModuleInsightPort {

	private final TrackModuleService moduleService;
	private final TrackAppMapper appMapper;
	private final TrackAnalysisService analysisService;

	public TrackInsightAdapter(TrackModuleService moduleService, TrackAppMapper appMapper,
							   TrackAnalysisService analysisService) {
		this.moduleService = moduleService;
		this.appMapper = appMapper;
		this.analysisService = analysisService;
	}

	@Override
	public String module() {
		return "track";
	}

	@Override
	public Map<String, Object> snapshot(Map<String, Object> request) {
		if (!moduleService.enabled()) {
			throw new ServiceException("埋点模块未启用");
		}
		List<TrackApp> apps = appMapper.selectListByQuery(QueryWrapper.create()
			.select("id", "app_key", "app_name")
			.orderBy("id", true)
			.limit(20));
		List<Map<String, Object>> rows = new ArrayList<>();
		for (TrackApp app : apps) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", app.getId());
			row.put("appKey", app.getAppKey());
			row.put("appName", app.getAppName());
			rows.add(row);
		}
		String appKey = text(request == null ? null : request.get("appKey"));
		if (appKey == null && !apps.isEmpty()) {
			appKey = apps.get(0).getAppKey();
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("source", "track_app");
		out.put("appCount", apps.size());
		out.put("apps", rows);
		out.put("appKey", appKey);
		if (appKey != null) {
			try {
				out.put("overview", analysisService.overview(appKey, 7));
			} catch (RuntimeException ex) {
				out.put("overviewError", ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
			}
		}
		return out;
	}

	private static String text(Object raw) {
		if (raw == null) {
			return null;
		}
		String s = String.valueOf(raw).trim();
		return s.isEmpty() || "null".equals(s) ? null : s;
	}
}
