package com.mugsun.boot.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 基线里原 V21 那一段（不连库）：三套方言都给表单两张表补 tenant_id、存量归平台租户，表单标识改为租户内唯一。
 * PG 实库验证见 {@link FlywayFreshInstallTest#formKeyIsUniquePerTenantAmongLiveRows()}。
 */
class FormTenantMigrationTest {

	private static String read(String dir) {
		return MigrationBaseline.section(dir, "V21__form_tenant.sql");
	}

	/** 去掉 -- 注释，只核对语句 */
	private static String sqlOnly(String script) {
		return script.lines().map(l -> l.replaceAll("--.*$", "")).reduce("", (a, b) -> a + b + "\n");
	}

	@ParameterizedTest(name = "{0}：两张表都加 tenant_id 并回填平台租户，回填在建索引之前，全局唯一的旧索引删掉")
	@ValueSource(strings = {"migration", "migration-og", "migration-dm"})
	void addsTenantColumnAndBackfillsPlatform(String dir) throws Exception {
		String sql = sqlOnly(read(dir));
		for (String table : new String[]{"sys_form", "sys_form_data"}) {
			assertThat(sql).as(dir + " " + table + " 加列")
				.containsPattern("ALTER TABLE " + table + " ADD (COLUMN )?(IF NOT EXISTS )?tenant_id VARCHAR\\(12\\)");
			assertThat(sql).as(dir + " " + table + " 回填")
				.contains("UPDATE " + table + " SET tenant_id = '000000' WHERE tenant_id IS NULL;");
		}
		assertThat(sql).contains("DROP INDEX IF EXISTS uk_form_key;");
		assertThat(sql.indexOf("UPDATE sys_form ")).isPositive().isLessThan(sql.indexOf("CREATE UNIQUE INDEX"));
	}

	@Test
	void pgScopesFormKeyToTenantForLiveRows() throws Exception {
		assertThat(sqlOnly(read("migration")))
			.contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_form_tenant_key ON sys_form (tenant_id, form_key) WHERE is_deleted = 0;");
	}

	@ParameterizedTest(name = "{0}：租户内唯一索引的每个键列都包 CASE WHEN is_deleted = 0（同 V7）")
	@ValueSource(strings = {"migration-og", "migration-dm"})
	void everyKeyColumnIsWrapped(String dir) throws Exception {
		String sql = sqlOnly(read(dir));
		assertThat(sql).contains("DROP INDEX IF EXISTS uk_form_tenant_key;");
		Matcher m = Pattern.compile("CREATE UNIQUE INDEX uk_form_tenant_key ON sys_form \\((.*?)\\);", Pattern.DOTALL).matcher(sql);
		assertThat(m.find()).as(dir + " 建索引").isTrue();
		String keys = m.group(1);
		assertThat(keys).contains("THEN tenant_id END").contains("THEN form_key END");
		int cases = keys.split("CASE WHEN is_deleted = 0", -1).length - 1;
		int columns = keys.split("\\bEND\\b", -1).length - 1;
		assertThat(cases).as(dir + " 键：" + keys).isEqualTo(2).isEqualTo(columns);
	}

	@Test
	void dmIsManualAndChecksColumnsBeforeDdl() throws Exception {
		String script = read("migration-dm");
		assertThat(script).startsWith("-- pg2dm: manual");
		String sql = sqlOnly(script);
		assertThat(sql.indexOf("ALL_TAB_COLUMNS")).isPositive().isLessThan(sql.indexOf("ALTER TABLE"));
	}

	@Test
	void ogChecksColumnsWithInformationSchema() throws Exception {
		String sql = sqlOnly(read("migration-og"));
		assertThat(sql.indexOf("information_schema.columns")).isPositive().isLessThan(sql.indexOf("ALTER TABLE"));
	}
}
