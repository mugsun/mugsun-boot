package com.mugsun.boot.gis.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.boot.gis.GisModuleService;
import com.mugsun.boot.gis.GisTileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 底图瓦片同源反代（密钥不进浏览器）。
 */
@RestController
@RequestMapping("/system/gis/tile")
@SaCheckLogin
public class GisTileController {

	private final GisModuleService moduleService;
	private final GisTileService tileService;

	public GisTileController(GisModuleService moduleService, GisTileService tileService) {
		this.moduleService = moduleService;
		this.tileService = tileService;
	}

	@GetMapping("/{provider}/{layer}/{z}/{x}/{y}")
	public ResponseEntity<byte[]> tile(@PathVariable String provider,
									   @PathVariable String layer,
									   @PathVariable int z,
									   @PathVariable int x,
									   @PathVariable int y) {
		moduleService.requireEnabled();
		return tileService.fetch(provider, layer, z, x, y);
	}
}
