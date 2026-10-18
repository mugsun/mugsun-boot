package com.mugsun.boot.gis;

import com.mugsun.boot.common.module.ConditionalOnModuleSwitch;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.gis.engine.GisUpstreamHttp;
import com.mugsun.boot.gis.tile.GisTileProperties;
import com.mugsun.boot.gis.tile.GisTileRateLimiter;
import com.mugsun.boot.gis.xyz.GisXyzGuard;
import com.mugsun.boot.gis.xyz.GisXyzProperties;
import com.mugsun.boot.gis.xyz.GisXyzTileService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * GIS 模块自动装配：classpath 有本 jar 且 {@code mugsun.gis.enabled} 为开时生效（规则见 {@link com.mugsun.boot.common.module.ModuleSwitch}：未设置或空值按开，非法值启动失败）。
 * Mapper 由启动类 {@code @MapperScan("com.mugsun.boot.**.mapper")} 统一扫描，此处不再重复声明。
 */
@AutoConfiguration
@ConditionalOnModuleSwitch("mugsun.gis.enabled")
@ComponentScan("com.mugsun.boot.gis")
@EnableConfigurationProperties({GisXyzProperties.class, GisTileProperties.class})
public class GisAutoConfiguration {

	@Bean
	public GisXyzTileService gisXyzTileService(GisXyzProperties props, GisUpstreamHttp http, ObjectMapper objectMapper) {
		return new GisXyzTileService(props, http, objectMapper, GisXyzGuard.SYSTEM_RESOLVER);
	}

	@Bean
	public GisTileRateLimiter gisTileRateLimiter(GisTileProperties props) {
		return new GisTileRateLimiter(props);
	}
}
