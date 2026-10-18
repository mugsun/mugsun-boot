package com.mugsun.boot.ai;

import com.mugsun.boot.common.module.ConditionalOnModuleSwitch;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * AI 模块自动装配：classpath 有本 jar 且 {@code mugsun.ai.enabled} 为开时生效（规则见 {@link com.mugsun.boot.common.module.ModuleSwitch}：未设置或空值按开，非法值启动失败）。
 * Mapper 由启动类 {@code @MapperScan("com.mugsun.boot.**.mapper")} 统一扫描。
 */
@AutoConfiguration
@ConditionalOnModuleSwitch("mugsun.ai.enabled")
@ComponentScan("com.mugsun.boot.ai")
public class AiAutoConfiguration {
}
