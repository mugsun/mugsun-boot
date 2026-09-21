package com.mugsun.boot.log;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记需追踪变更的字段：value 中文名；dict 指定字典 code 时将值翻译为字典标签。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditField {

	/** 字段中文名 */
	String value();

	/** 字典 code（非空时按字典翻译值为中文标签） */
	String dict() default "";
}
