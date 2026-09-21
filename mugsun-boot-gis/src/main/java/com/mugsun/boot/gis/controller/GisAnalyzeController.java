package com.mugsun.boot.gis.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.mugsun.boot.gis.GisAnalyzeService;
import com.mugsun.boot.gis.GisConstants;
import com.mugsun.boot.gis.GisModuleService;
import com.mugsun.core.tool.api.R;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 空间分析：缓冲 / 质心 / 外包 / 面积长度 / 距离 / 相交包含 / 融合差集 / 简化 / 凸包。
 */
@RestController
@RequestMapping("/system/gis/geo")
@SaCheckLogin
public class GisAnalyzeController {

	private final GisModuleService moduleService;
	private final GisAnalyzeService analyzeService;

	public GisAnalyzeController(GisModuleService moduleService, GisAnalyzeService analyzeService) {
		this.moduleService = moduleService;
		this.analyzeService = analyzeService;
	}

	@PostMapping("/analyze")
	@SaCheckPermission(value = {
		GisConstants.PERM_ANALYZE, GisConstants.PERM_WORKSPACE, GisConstants.PERM_LAYER_LIST
	}, mode = SaMode.OR)
	public R<Map<String, Object>> analyze(@RequestBody Map<String, Object> body) {
		moduleService.requireEnabled();
		return R.data(analyzeService.analyze(body));
	}
}
