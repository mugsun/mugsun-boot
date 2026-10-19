package com.mugsun.boot.demo;

/**
 * 只读演示模式常量。开启方式：{@code --spring.profiles.active=demo}（application-demo.yml 置 {@code mugsun.demo.enabled=true}）。
 */
public final class DemoConstants {

	/** 演示模式总开关 */
	public static final String ENABLED_PROPERTY = "mugsun.demo.enabled";
	/** owner 一次性迁移开关（非 demo profile 使用） */
	public static final String MIGRATE_ONLY_PROPERTY = "mugsun.demo.migrate-only";
	/** 前缀 */
	public static final String PREFIX = "mugsun.demo";
	/** 拒绝写请求时返回的业务错误码（响应体 errorCode 字段） */
	public static final String ERROR_CODE = "DEMO_READONLY";
	/** 拒绝写请求时返回的提示 */
	public static final String MESSAGE = "演示环境只读";

	private DemoConstants() {
	}
}
