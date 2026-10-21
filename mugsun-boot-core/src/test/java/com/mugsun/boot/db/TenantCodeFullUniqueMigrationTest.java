package com.mugsun.boot.db;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** V11（不连库）：三套方言都把 uk_tenant_code、uk_tenant_ds_code 改回全量唯一，查重在任何 DDL 之前（B1） */
class TenantCodeFullUniqueMigrationTest {

	private static String read(String dir) throws Exception {
		try (InputStream in = TenantCodeFullUniqueMigrationTest.class.getResourceAsStream(
			"/db/" + dir + "/V11__tenant_code_full_unique.sql")) {
			assertThat(in).as(dir + " 缺少 V11").isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"migration", "migration-og", "migration-dm"})
	void restoresFullUniqueAfterDuplicateCheck(String dir) throws Exception {
		String sql = read(dir);
		String body = sql.lines().filter(l -> !l.trim().startsWith("--")).reduce("", (a, b) -> a + b + "\n");
		assertThat(body).contains("CREATE UNIQUE INDEX uk_tenant_code ON sys_tenant (tenant_code);");
		assertThat(body).contains("CREATE UNIQUE INDEX uk_tenant_ds_code ON sys_tenant_datasource (tenant_code);");
		assertThat(body).doesNotContain("is_deleted");
		assertThat(body.indexOf("V11 升级中止")).isPositive().isLessThan(body.indexOf("DROP INDEX"));
	}
}
