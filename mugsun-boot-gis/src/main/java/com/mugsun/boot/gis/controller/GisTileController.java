package com.mugsun.boot.gis.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.web.ClientIpResolver;
import com.mugsun.boot.gis.GisModuleService;
import com.mugsun.boot.gis.GisTileService;
import com.mugsun.boot.gis.tile.GisTileRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 底图瓦片同源反代（密钥不进浏览器）。按用户、按 IP 限流，超限返回 429。
 */
@RestController
@RequestMapping("/system/gis/tile")
@SaCheckLogin
public class GisTileController {

	private final GisModuleService moduleService;
	private final GisTileService tileService;
	private final GisTileRateLimiter rateLimiter;

	public GisTileController(GisModuleService moduleService, GisTileService tileService,
							 GisTileRateLimiter rateLimiter) {
		this.moduleService = moduleService;
		this.tileService = tileService;
		this.rateLimiter = rateLimiter;
	}

	@GetMapping("/{provider}/{layer}/{z}/{x}/{y}")
	public ResponseEntity<byte[]> tile(@PathVariable String provider,
									   @PathVariable String layer,
									   @PathVariable int z,
									   @PathVariable int x,
									   @PathVariable int y,
									   HttpServletRequest request) {
		moduleService.requireEnabled();
		if (!rateLimiter.tryAcquire(StpUtil.getLoginIdAsString(), ClientIpResolver.clientIp(request))) {
			return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
				.header(HttpHeaders.RETRY_AFTER, Long.toString(rateLimiter.retryAfterSeconds()))
				.header("X-Content-Type-Options", "nosniff")
				.build();
		}
		return tileService.fetch(provider, layer, z, x, y);
	}
}
