package com.mugsun.boot.oauth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注开放接口所需的授权范围（scope）。
 * 令牌所含 scope 须包含该值方可放行，否则 403。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OpenScope {

	/** 所需 scope，如 user:read */
	String value();
}
