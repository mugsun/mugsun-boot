package com.mugsun.boot.gis.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.boot.gis.GisModuleService;
import com.mugsun.boot.gis.GisTilesetService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内置三维切片下发（3D Tiles）。与瓦片反代一样只要登录态：Cesium 直接发请求，权限靠登录兜底。
 */
@RestController
@RequestMapping("/system/gis/tileset")
@SaCheckLogin
public class GisTilesetController {

	private final GisModuleService moduleService;
	private final GisTilesetService tilesetService;

	public GisTilesetController(GisModuleService moduleService, GisTilesetService tilesetService) {
		this.moduleService = moduleService;
		this.tilesetService = tilesetService;
	}

	/** 切片清单入口，Cesium 的 Cesium3DTileset.fromUrl 指向这里 */
	@GetMapping("/{code}/tileset.json")
	public ResponseEntity<byte[]> manifest(@PathVariable String code) {
		moduleService.requireEnabled();
		return tilesetService.manifest(code);
	}

	/** 清单内引用的切片体（b3dm / glb / pnts / cmpt）。清单里写相对 uri，须与清单同级 */
	@GetMapping("/{code}/{file}")
	public ResponseEntity<byte[]> content(@PathVariable String code, @PathVariable String file) {
		moduleService.requireEnabled();
		return tilesetService.content(code, file);
	}
}
