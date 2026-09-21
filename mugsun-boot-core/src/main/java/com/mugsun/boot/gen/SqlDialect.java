package com.mugsun.boot.gen;

import com.mybatisflex.core.dialect.DbType;

/**
 * 建表 DDL + 运行时 SQL 方言族：将 Flex {@link DbType} 归并为三大兼容族，屏蔽国产库/主流库差异。
 * <ul><li>{@link #POSTGRES}——PG 系：PostgreSQL / 金仓 KingbaseES / openGauss / GaussDB / 瀚高 HighGo / Greenplum / 海量 VastBase 等（PG 线协议与语法兼容）；</li>
 * <li>{@link #ORACLE}——Oracle 系：Oracle / 达梦 DM / OceanBase(Oracle 模式)（NUMBER/VARCHAR2/CLOB，未加引号标识符按大写存储）；</li>
 * <li>{@link #MYSQL}——MySQL 系：MySQL / MariaDB / OceanBase(MySQL 模式) / GoldenDB。</li></ul>
 * 目标库由 {@code DbTypeUtil.getDbType(dataSource)} 探测后自动归族；建表类型与 §5 运行时 SQL 片段按族出型。
 */
public enum SqlDialect {

	POSTGRES("BIGINT", "INT4", "TIMESTAMP", "NUMERIC(18,4)", "FLOAT8", "TEXT", "VARCHAR(255)"),
	ORACLE("NUMBER(19)", "NUMBER(10)", "TIMESTAMP", "NUMBER(18,4)", "BINARY_DOUBLE", "CLOB", "VARCHAR2(255)"),
	MYSQL("BIGINT", "INT", "DATETIME", "DECIMAL(18,4)", "DOUBLE", "TEXT", "VARCHAR(255)");

	private final String bigintType;
	private final String intType;
	private final String timestampType;
	private final String decimalType;
	private final String doubleType;
	private final String textType;
	private final String varcharType;

	SqlDialect(String bigintType, String intType, String timestampType, String decimalType,
			String doubleType, String textType, String varcharType) {
		this.bigintType = bigintType;
		this.intType = intType;
		this.timestampType = timestampType;
		this.decimalType = decimalType;
		this.doubleType = doubleType;
		this.textType = textType;
		this.varcharType = varcharType;
	}

	/** Flex 探测的 DbType 归族；未识别库回退平台主库族 PG，保证不因新库型中断建表。 */
	public static SqlDialect of(DbType dbType) {
		if (dbType == null) {
			return POSTGRES;
		}
		return switch (dbType) {
			case ORACLE, ORACLE_12C, DM, OCEAN_BASE_ORACLE -> ORACLE;
			case MYSQL, MARIADB, OCEAN_BASE, GOLDENDB -> MYSQL;
			// PG 系(含金仓 KINGBASE_ES/openGauss/GaussDB/瀚高/Greenplum/海量 等)与未知库统一按 PG 出型
			default -> POSTGRES;
		};
	}

	/** 是否 Oracle 兼容族（含达梦）——§5 MERGE / SYSTIMESTAMP / ALTER SESSION 等走此分支 */
	public boolean oracleFamily() {
		return this == ORACLE;
	}

	/** 库内当前时间表达式：PG/MySQL {@code now()}，Oracle/达梦 {@code SYSTIMESTAMP} */
	public String currentTimestamp() {
		return oracleFamily() ? "SYSTIMESTAMP" : "now()";
	}

	/** DROP TABLE 语句：Oracle 系无 IF EXISTS */
	public String dropTable(String tableName) {
		return oracleFamily() ? "DROP TABLE " + tableName : "DROP TABLE IF EXISTS " + tableName;
	}

	/**
	 * 列级「非空默认 0」片段：PG/MySQL {@code NOT NULL DEFAULT 0}，
	 * Oracle 系要求 {@code DEFAULT … NOT NULL} 顺序。
	 */
	public String notNullDefaultZero() {
		return oracleFamily() ? " DEFAULT 0 NOT NULL" : " NOT NULL DEFAULT 0";
	}

	/**
	 * SCHEMA 隔离连接初始化 SQL（schemaName 须已校验为合法标识符）。
	 * PG：{@code SET search_path TO x}；Oracle/达梦：{@code ALTER SESSION SET CURRENT_SCHEMA = x}。
	 */
	public String schemaInitSql(String schemaName) {
		if (oracleFamily()) {
			return "ALTER SESSION SET CURRENT_SCHEMA = " + schemaName;
		}
		return "SET search_path TO " + schemaName;
	}

	/**
	 * 校验 schema/用户是否存在的探测 SQL（单占位符）。
	 * PG：information_schema.schemata；Oracle/达梦：ALL_USERS（用户即 schema）。
	 */
	public String schemaExistsSql() {
		if (oracleFamily()) {
			return "SELECT 1 FROM ALL_USERS WHERE USERNAME = UPPER(?)";
		}
		return "SELECT 1 FROM information_schema.schemata WHERE schema_name = ?";
	}

	/** 单行限制子句：PG/MySQL {@code LIMIT 1}，Oracle/达梦 {@code FETCH FIRST 1 ROWS ONLY} */
	public String limitOne() {
		return oracleFamily() ? " FETCH FIRST 1 ROWS ONLY" : " LIMIT 1";
	}

	/**
	 * 将表达式转为短字符串。达梦 {@code CAST(... AS VARCHAR)} 无长度非法；
	 * {@code VARCHAR(64)} 在 PG/金仓/达梦/MySQL 均可。
	 */
	public String castVarchar(String expr) {
		return "CAST(" + expr + " AS VARCHAR(64))";
	}

	/** 雪花主键/Long 列类型 */
	public String bigintType() {
		return bigintType;
	}

	/** 时间戳列类型（审计列 create_time/update_time 用） */
	public String timestampType() {
		return timestampType;
	}

	/** 整型列类型（is_deleted 逻辑删除列用） */
	public String intType() {
		return intType;
	}

	/** 业务列 Java 类型 → 目标库 SQL 类型（largeText：长文本控件落大字段） */
	public String columnType(String javaType, boolean largeText) {
		String jt = javaType == null ? "String" : javaType;
		return switch (jt) {
			case "Integer", "Short", "Boolean" -> intType;
			case "Long" -> bigintType;
			case "BigDecimal" -> decimalType;
			case "Double", "Float" -> doubleType;
			case "LocalDateTime", "Date", "Timestamp" -> timestampType;
			case "LocalDate" -> "DATE";
			default -> largeText ? textType : varcharType;
		};
	}
}
