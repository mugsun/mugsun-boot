package com.mugsun.boot.datascope;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据权限标记：标注在查询方法上激活行级数据权限。{@link DataScopeAspect} 预解析当前用户全部角色范围并激活，
 * 方法内受控表（{@link DataScopeTables}）查询由 {@link MugsunDataScopeDialect} 自动注入 OR 并集条件，无需手工 apply。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DataScope {
}
