package com.mugsun.boot.gen;

import com.mybatisflex.core.dialect.DbType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 方言族归并与 §5 运行时 SQL 片段（不启容器）：保证 PG 路径保留 ON CONFLICT/now()，
 * Oracle/达梦路径出 MERGE/SYSTIMESTAMP/ALTER SESSION 等。
 */
class SqlDialectTest {

	@Test
	void ofMapsDamengAndOracleToOracleFamily() {
		assertThat(SqlDialect.of(DbType.DM)).isEqualTo(SqlDialect.ORACLE);
		assertThat(SqlDialect.of(DbType.ORACLE)).isEqualTo(SqlDialect.ORACLE);
		assertThat(SqlDialect.of(DbType.ORACLE_12C)).isEqualTo(SqlDialect.ORACLE);
		assertThat(SqlDialect.of(DbType.OCEAN_BASE_ORACLE)).isEqualTo(SqlDialect.ORACLE);
	}

	@Test
	void ofMapsPgFamilyAndKingbaseToPostgres() {
		assertThat(SqlDialect.of(DbType.POSTGRE_SQL)).isEqualTo(SqlDialect.POSTGRES);
		assertThat(SqlDialect.of(DbType.KINGBASE_ES)).isEqualTo(SqlDialect.POSTGRES);
		assertThat(SqlDialect.of(DbType.OPENGAUSS)).isEqualTo(SqlDialect.POSTGRES);
		assertThat(SqlDialect.of(null)).isEqualTo(SqlDialect.POSTGRES);
	}

	@Test
	void currentTimestampBranches() {
		assertThat(SqlDialect.POSTGRES.currentTimestamp()).isEqualTo("now()");
		assertThat(SqlDialect.MYSQL.currentTimestamp()).isEqualTo("now()");
		assertThat(SqlDialect.ORACLE.currentTimestamp()).isEqualTo("SYSTIMESTAMP");
	}

	@Test
	void dropTableAndDefaultOrder() {
		assertThat(SqlDialect.POSTGRES.dropTable("t")).isEqualTo("DROP TABLE IF EXISTS t");
		assertThat(SqlDialect.ORACLE.dropTable("t")).isEqualTo("DROP TABLE t");
		assertThat(SqlDialect.POSTGRES.notNullDefaultZero()).isEqualTo(" NOT NULL DEFAULT 0");
		assertThat(SqlDialect.ORACLE.notNullDefaultZero()).isEqualTo(" DEFAULT 0 NOT NULL");
	}

	@Test
	void schemaInitAndExistsSql() {
		assertThat(SqlDialect.POSTGRES.schemaInitSql("tenant_a")).isEqualTo("SET search_path TO tenant_a");
		assertThat(SqlDialect.ORACLE.schemaInitSql("TENANT_A"))
			.isEqualTo("ALTER SESSION SET CURRENT_SCHEMA = TENANT_A");
		assertThat(SqlDialect.POSTGRES.schemaExistsSql()).contains("information_schema.schemata");
		assertThat(SqlDialect.ORACLE.schemaExistsSql()).contains("ALL_USERS");
	}

	@Test
	void upsertTableColumnPgKeepsOnConflict() {
		String sql = RuntimeSql.upsertTableColumn(SqlDialect.POSTGRES);
		assertThat(sql).contains("on conflict").contains("now()").doesNotContain("MERGE");
	}

	@Test
	void upsertTableColumnOracleUsesMergeAndSystimestamp() {
		String sql = RuntimeSql.upsertTableColumn(SqlDialect.ORACLE);
		assertThat(sql).containsIgnoringCase("MERGE INTO").contains("SYSTIMESTAMP")
			.doesNotContain("on conflict").doesNotContain("now()");
	}

	@Test
	void upsertWorkbenchAndSerialAndFlow() {
		assertThat(RuntimeSql.upsertWorkbenchShortcut(SqlDialect.ORACLE)).containsIgnoringCase("MERGE INTO");
		assertThat(RuntimeSql.upsertSerialRecord(SqlDialect.ORACLE)).contains("GREATEST")
			.contains("SYSTIMESTAMP");
		assertThat(RuntimeSql.upsertSerialRecord(SqlDialect.POSTGRES)).contains("on conflict")
			.contains("greatest");
		assertThat(RuntimeSql.insertFlowUser(SqlDialect.ORACLE)).contains("SYSTIMESTAMP");
		assertThat(RuntimeSql.insertFlowUser(SqlDialect.POSTGRES)).contains("now()");
	}

	@Test
	void noticeReadPgUsesXmaxOracleUsesAppLevelSql() {
		assertThat(RuntimeSql.upsertNoticeReadPg()).contains("xmax").contains("on conflict");
		assertThat(RuntimeSql.insertNoticeRead(SqlDialect.ORACLE)).contains("SYSTIMESTAMP")
			.doesNotContain("on conflict");
		assertThat(RuntimeSql.bumpNoticeRead(SqlDialect.ORACLE)).startsWith("update sys_notice_read");
	}

	@Test
	void ofUrlDetectsDmAndPostgres() {
		assertThat(DbDialects.ofUrl("jdbc:dm://127.0.0.1:5236")).isEqualTo(SqlDialect.ORACLE);
		assertThat(DbDialects.ofUrl("jdbc:postgresql://127.0.0.1:5432/mugsun")).isEqualTo(SqlDialect.POSTGRES);
		assertThat(DbDialects.ofUrl(null)).isEqualTo(SqlDialect.POSTGRES);
	}

	@Test
	void limitOneBranches() {
		assertThat(SqlDialect.POSTGRES.limitOne()).isEqualTo(" LIMIT 1");
		assertThat(SqlDialect.ORACLE.limitOne()).isEqualTo(" FETCH FIRST 1 ROWS ONLY");
	}

	@Test
	void castVarcharUsesLengthForAllFamilies() {
		assertThat(SqlDialect.ORACLE.castVarchar("su.id")).isEqualTo("CAST(su.id AS VARCHAR(64))");
		assertThat(SqlDialect.POSTGRES.castVarchar("id")).isEqualTo("CAST(id AS VARCHAR(64))");
	}
}
