package com.mugsun.boot.track;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;

/**
 * 埋点模块自动装配：classpath 有本 jar 且 {@code mugsun.track.enabled=true}（缺省 true）时生效。
 * Mapper 由启动类 {@code @MapperScan("com.mugsun.boot.**.mapper")} 统一扫描，此处不再重复声明。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "mugsun.track", name = "enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan("com.mugsun.boot.track")
public class TrackAutoConfiguration {
}
