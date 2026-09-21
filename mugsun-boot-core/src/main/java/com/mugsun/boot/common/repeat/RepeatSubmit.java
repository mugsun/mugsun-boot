package com.mugsun.boot.common.repeat;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 防重复提交：按 用户+方法+参数指纹 判重。
 * interval > 0：该毫秒窗口内只放行一次；interval = 0：仅上次执行完成后才可再次提交。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RepeatSubmit {

	/** 判重时间窗（毫秒），0 表示"上次执行完才可再来" */
	long interval() default 3000;

	/** 拦截提示 */
	String message() default "请勿重复提交";
}
