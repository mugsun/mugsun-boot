package com.mugsun.boot.ai;

import com.mugsun.boot.common.module.ConditionalOnModuleSwitch;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * AI 模块自动装配：classpath 有本 jar 且 {@code mugsun.ai.enabled} 为开时生效。
 * 开关规则（{@link com.mugsun.boot.common.module.ModuleSwitch#parseStrict}）：未设置按开；设置后只认 true/false，空值等其它值启动失败。
 * 「其它值」包括设置了但为空、1/0、带首尾空格的值（true/false 本身不区分大小写）；绝不悄悄开启或关闭模块。
 * Mapper 由启动类 {@code @MapperScan("com.mugsun.boot.**.mapper")} 统一扫描。
 */
@AutoConfiguration
@ConditionalOnModuleSwitch("mugsun.ai.enabled")
@ComponentScan("com.mugsun.boot.ai")
public class AiAutoConfiguration {
}
