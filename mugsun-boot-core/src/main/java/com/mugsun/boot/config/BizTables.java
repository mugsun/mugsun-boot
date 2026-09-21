package com.mugsun.boot.config;

import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 业务表名（可带 schema）。金仓裸 {@code sys_*} 会进 {@code SYS_CATALOG}，须限定 {@code mugsun.sys_*}。
 */
@Component
public class BizTables implements EnvironmentAware {

	private static volatile String schema = "";

	@Override
	public void setEnvironment(@NonNull Environment environment) {
		schema = environment.getProperty("mugsun.db.default-schema", "");
	}

	/** 表名，配置了 default-schema 时加前缀 */
	public static String of(String table) {
		if (!StringUtils.hasText(table) || table.indexOf('.') >= 0 || !StringUtils.hasText(schema)) {
			return table;
		}
		return schema + "." + table;
	}
}
