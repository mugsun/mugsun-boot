package com.mugsun.boot.gen;

import com.mybatisflex.core.FlexGlobalConfig;
import com.mybatisflex.core.dialect.DbType;
import com.mybatisflex.core.dialect.DbTypeUtil;

import javax.sql.DataSource;

/**
 * 运行时方言族解析：优先读 Flex 主库已探测的 {@link DbType}，亦可按 DataSource / JDBC URL 归族。
 * 失败一律回退 {@link SqlDialect#POSTGRES}，保证 PG 主路径零回归。
 */
public final class DbDialects {

	private DbDialects() {
	}

	/** 当前主库方言族（应用启动后 Flex 已探测） */
	public static SqlDialect current() {
		try {
			DbType dbType = FlexGlobalConfig.getDefaultConfig().getDbType();
			return SqlDialect.of(dbType);
		} catch (Exception e) {
			return SqlDialect.POSTGRES;
		}
	}

	/** 按数据源 JDBC 元数据/URL 归族（租户独立源探活前可用） */
	public static SqlDialect of(DataSource dataSource) {
		try {
			return SqlDialect.of(DbTypeUtil.getDbType(dataSource));
		} catch (Exception e) {
			return SqlDialect.POSTGRES;
		}
	}

	/** 按 JDBC URL 归族（无需建连；URL 无法识别时回退 PG） */
	public static SqlDialect ofUrl(String jdbcUrl) {
		if (jdbcUrl == null || jdbcUrl.isBlank()) {
			return SqlDialect.POSTGRES;
		}
		try {
			return SqlDialect.of(DbTypeUtil.parseDbType(jdbcUrl));
		} catch (Exception e) {
			return SqlDialect.POSTGRES;
		}
	}
}
