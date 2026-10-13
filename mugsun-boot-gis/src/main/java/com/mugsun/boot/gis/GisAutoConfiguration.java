package com.mugsun.boot.gis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.gis.engine.GisUpstreamHttp;
import com.mugsun.boot.gis.xyz.GisXyzGuard;
import com.mugsun.boot.gis.xyz.GisXyzProperties;
import com.mugsun.boot.gis.xyz.GisXyzTileService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/**
 * GIS 模块自动装配：classpath 有本 jar 且 {@code mugsun.gis.enabled=true}（缺省 true）时生效。
 * Mapper 由启动类 {@code @MapperScan("com.mugsun.boot.**.mapper")} 统一扫描，此处不再重复声明。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "mugsun.gis", name = "enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan("com.mugsun.boot.gis")
@EnableConfigurationProperties(GisXyzProperties.class)
public class GisAutoConfiguration {

	@Bean
	public GisXyzTileService gisXyzTileService(GisXyzProperties props, GisUpstreamHttp http, ObjectMapper objectMapper) {
		return new GisXyzTileService(props, http, objectMapper, GisXyzGuard.SYSTEM_RESOLVER);
	}
}
