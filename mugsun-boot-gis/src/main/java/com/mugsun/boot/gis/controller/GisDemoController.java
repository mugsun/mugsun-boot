package com.mugsun.boot.gis.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import com.mugsun.boot.gis.GisConstants;
import com.mugsun.boot.gis.GisDemoCatalog;
import com.mugsun.boot.gis.GisModuleService;
import com.mugsun.core.tool.api.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 内置 GIS 示例：打开即可叠加，不写图层库。
 */
@RestController
@RequestMapping("/system/gis/demo")
@SaCheckLogin
public class GisDemoController {

	private final GisModuleService moduleService;
	private final GisDemoCatalog demoCatalog;

	public GisDemoController(GisModuleService moduleService, GisDemoCatalog demoCatalog) {
		this.moduleService = moduleService;
		this.demoCatalog = demoCatalog;
	}

	@GetMapping("/list")
	@SaCheckPermission(value = {
		GisConstants.PERM_DEMO, GisConstants.PERM_WORKSPACE, GisConstants.PERM_LAYER_LIST, GisConstants.PERM_ANALYZE
	}, mode = SaMode.OR)
	public R<List<Map<String, Object>>> list() {
		moduleService.requireEnabled();
		return R.data(demoCatalog.list());
	}

	@GetMapping("/{code}")
	@SaCheckPermission(value = {
		GisConstants.PERM_DEMO, GisConstants.PERM_WORKSPACE, GisConstants.PERM_LAYER_LIST, GisConstants.PERM_ANALYZE
	}, mode = SaMode.OR)
	public R<Map<String, Object>> detail(@PathVariable String code) {
		moduleService.requireEnabled();
		return R.data(demoCatalog.collection(code));
	}
}
