package com.mugsun.boot.ai;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;

/**
 * AI 模块自动装配：classpath 有本 jar 且 {@code mugsun.ai.enabled=true}（缺省 true）时生效。
 * Mapper 由启动类 {@code @MapperScan("com.mugsun.boot.**.mapper")} 统一扫描。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "mugsun.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan("com.mugsun.boot.ai")
public class AiAutoConfiguration {
}
