package com.mugsun.boot.datasource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注需按当前租户隔离策略自动路由数据源的业务类/方法。
 * <p>由 {@link TenantRoutedAspect} 环绕解析当前租户的路由键并压栈，替代手工 {@code DataSourceKey.use} 包裹。
 * 无独立源配置的租户（FIELD 策略）自然回落主库 + tenant_id 字段隔离。
 * <p><b>使用纪律</b>：标注方法内的<em>全部</em> DB 访问都会被路由到该租户的独立源。仅标注只访问「按租户路由的业务实体」
 * 的类/方法；若方法内需访问主库/sys_* 等共享表，请对该辅助操作用 {@code DataSourceKey.use("primary", ...)} 局部覆盖，
 * 避免被静默路由进租户独立库（该库无此表）。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantRouted {
}
