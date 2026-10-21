package com.mugsun.boot.datasource;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 租户数据源注册日志里的 URL 不带账号、口令等凭据参数（B5） */
class JdbcUrlMaskerTest {

	@Test
	void masksUserinfo() {
		assertThat(JdbcUrlMasker.mask("jdbc:postgresql://admin:S3cret@db.local:5432/t1?sslmode=require"))
			.isEqualTo("jdbc:postgresql://***@db.local:5432/t1?sslmode=require")
			.doesNotContain("S3cret").doesNotContain("admin");
	}

	@Test
	void masksCredentialQueryParams() {
		String masked = JdbcUrlMasker.mask(
			"jdbc:dm://10.0.0.1:5236?schema=T1&user=SYSDBA&password=S3cret&sslKeyPassword=k&accessToken=t&connectTimeout=5000");
		assertThat(masked).isEqualTo(
			"jdbc:dm://10.0.0.1:5236?schema=T1&user=***&password=***&sslKeyPassword=***&accessToken=***&connectTimeout=5000");
	}

	@Test
	void masksSemicolonParamsAndOracleThin() {
		assertThat(JdbcUrlMasker.mask("jdbc:sqlserver://h:1433;databaseName=a;user=sa;password=S3cret;encrypt=true"))
			.isEqualTo("jdbc:sqlserver://h:1433;databaseName=a;user=***;password=***;encrypt=true");
		assertThat(JdbcUrlMasker.mask("jdbc:oracle:thin:scott/tiger@//h:1521/orcl"))
			.isEqualTo("jdbc:oracle:thin:***@//h:1521/orcl");
	}

	@Test
	void leavesPlainUrlAndNullAlone() {
		assertThat(JdbcUrlMasker.mask("jdbc:opengauss://h:5432/db?currentSchema=t1")).isEqualTo("jdbc:opengauss://h:5432/db?currentSchema=t1");
		assertThat(JdbcUrlMasker.mask(null)).isNull();
	}
}
