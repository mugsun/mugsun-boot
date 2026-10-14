package com.mugsun.boot.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 只读演示模式（{@code mugsun.demo.enabled=true}）下不装配的组件：
 * 启动期写库（建列/播种）和写库的定时任务。演示环境应用只持只读账号，这些写入由 owner 账号一次性执行
 * （{@code --mugsun.demo.migrate-only=true}，见 {@link DemoMigrateOnlyRunner}）。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = DemoConstants.ENABLED_PROPERTY, havingValue = "false", matchIfMissing = true)
public @interface ConditionalOnNotDemo {
}
