package com.mugsun.boot;

import com.alicp.jetcache.anno.config.EnableMethodCache;
import org.dromara.x.file.storage.spring.EnableFileStorage;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Mugsun 单体版启动入口
 *
 * <p>扫描根为 {@code com.mugsun.boot}，但排除 {@code gis}/{@code track} 包——二者由各自
 * AutoConfiguration 在 jar 存在且模块开关开启时再扫描，保证未引入可选模块时核心零依赖。
 */
@SpringBootApplication
@ComponentScan(
	basePackages = "com.mugsun.boot",
	excludeFilters = {
		@ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.mugsun\\.boot\\.gis\\..*"),
		@ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.mugsun\\.boot\\.track\\..*")
	}
)
@EnableAsync
@EnableScheduling
@EnableFileStorage
@EnableMethodCache(basePackages = "com.mugsun.boot")
@MapperScan("com.mugsun.boot.**.mapper")
public class MugsunApplication {

	public static void main(String[] args) {
		SpringApplication.run(MugsunApplication.class, args);
	}
}
