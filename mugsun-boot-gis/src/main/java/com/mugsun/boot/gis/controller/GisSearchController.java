package com.mugsun.boot.gis.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.boot.gis.GisModuleService;
import com.mugsun.boot.gis.GisSearchService;
import com.mugsun.core.tool.api.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 地名搜索 / 逆地理编码（服务端持 Key）。
 */
@RestController
@RequestMapping("/system/gis")
@SaCheckLogin
public class GisSearchController {

	private final GisModuleService moduleService;
	private final GisSearchService searchService;

	public GisSearchController(GisModuleService moduleService, GisSearchService searchService) {
		this.moduleService = moduleService;
		this.searchService = searchService;
	}

	@GetMapping("/search")
	public R<List<Map<String, Object>>> search(@RequestParam(required = false) String q,
											   @RequestParam(required = false) Double lon,
											   @RequestParam(required = false) Double lat,
											   @RequestParam(required = false) String provider) {
		moduleService.requireEnabled();
		return R.data(searchService.search(q, lon, lat, provider));
	}

	@GetMapping("/reverse")
	public R<Map<String, Object>> reverse(@RequestParam double lon,
										  @RequestParam double lat,
										  @RequestParam(required = false) String provider) {
		moduleService.requireEnabled();
		return R.data(searchService.reverse(lon, lat, provider));
	}
}
