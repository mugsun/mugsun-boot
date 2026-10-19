package com.mugsun.boot.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V7（openGauss / 达梦）：唯一索引只约束未删除的行。不连库，只核对脚本写法；实库验证见 PR 说明与
 * {@link FlywayUpgradeFrom010Test#deletedRowsDoNotBlockUniqueKeysAfterV7()}（PG）。
 */
class UniqueIndexDeleteFlagMigrationTest {

	private static final List<String> INDEXES = List.of("uk_tenant_code", "uk_user_tenant_username", "uk_user_role",
		"uk_role_menu", "uk_role_dept", "uk_tenant_ds_code", "uk_user_tenant_phone");

	private static String read(String path) throws Exception {
		try (InputStream in = UniqueIndexDeleteFlagMigrationTest.class.getClassLoader().getResourceAsStream(path)) {
			assertThat(in).as(path).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String v7(String dir) throws Exception {
		return read("db/" + dir + "/V7__unique_index_delete_flag.sql");
	}

	/** 去掉 -- 注释，便于只核对语句 */
	private static String sqlOnly(String script) {
		return script.lines().map(l -> l.replaceAll("--.*$", "")).reduce("", (a, b) -> a + b + "\n");
	}

	@Test
	@DisplayName("三套方言都有 V7（同号）")
	void sameVersionInAllDialects() throws Exception {
		assertThat(v7("migration")).contains("SELECT 1");
		assertThat(v7("migration-og")).isNotBlank();
		assertThat(v7("migration-dm")).startsWith("-- pg2dm: manual");
	}

	@ParameterizedTest(name = "{0}：7 个索引都先删后建为唯一索引，每个键列都包 CASE WHEN is_deleted = 0")
	@ValueSource(strings = {"migration-og", "migration-dm"})
	void everyKeyColumnIsWrapped(String dir) throws Exception {
		String sql = sqlOnly(v7(dir));
		for (String idx : INDEXES) {
			assertThat(sql).as(dir + " drop " + idx).contains("DROP INDEX IF EXISTS " + idx + ";");
			Matcher m = Pattern.compile("CREATE UNIQUE INDEX " + idx + " ON \\w+ \\((.*?)\\);", Pattern.DOTALL).matcher(sql);
			assertThat(m.find()).as(dir + " create " + idx).isTrue();
			String keys = m.group(1);
			// 每个键都是 CASE ... END，不允许裸列，也不允许 (列, is_deleted) 这种删两次就撞键的写法
			int cases = keys.split("CASE WHEN is_deleted = 0", -1).length - 1;
			int columns = keys.split("\\bEND\\b", -1).length - 1;
			assertThat(cases).as(dir + " " + idx + " 键：" + keys).isPositive().isEqualTo(columns);
			assertThat(keys).doesNotContainPattern(",\\s*is_deleted\\s*\\)?\\s*$");
		}
	}

	@ParameterizedTest(name = "{0}：手机号键只在未删除且非空非空串时取值")
	@ValueSource(strings = {"migration-og", "migration-dm"})
	void phoneIgnoresNullAndEmpty(String dir) throws Exception {
		String sql = sqlOnly(v7(dir));
		Matcher m = Pattern.compile("CREATE UNIQUE INDEX uk_user_tenant_phone ON sys_user \\((.*?)\\);", Pattern.DOTALL).matcher(sql);
		assertThat(m.find()).isTrue();
		assertThat(m.group(1).split("CASE WHEN is_deleted = 0 AND phone IS NOT NULL AND phone <> '' THEN", -1)).hasSize(3);
	}

	@ParameterizedTest(name = "{0}：先查重复手机号再动索引，报错指向升级说明")
	@ValueSource(strings = {"migration-og", "migration-dm"})
	void duplicatePhoneCheckComesFirst(String dir) throws Exception {
		String sql = sqlOnly(v7(dir));
		int check = sql.indexOf("GROUP BY tenant_id, phone");
		int firstDrop = sql.indexOf("DROP INDEX");
		assertThat(check).isPositive().isLessThan(firstDrop);
		assertThat(sql).contains("V7 升级中止", "https://docs.mugsun.com/boot/upgrade", "同租户重复手机号");
	}

	@Test
	@DisplayName("PG 的 V1 已是部分唯一索引，V7 在 PG 上不需要改（口径与 og/dm 一致）")
	void pgAlreadyPartial() throws Exception {
		String v1 = read("db/migration/V1__baseline_0_1.sql");
		assertThat(v1).contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_code ON sys_tenant (tenant_code) WHERE is_deleted = 0;")
			.contains("CREATE UNIQUE INDEX uk_user_tenant_username ON sys_user (tenant_id, username) WHERE is_deleted = 0;")
			.contains("uk_user_role ON sys_user_role (user_id, role_id) WHERE is_deleted = 0;")
			.contains("uk_role_menu ON sys_role_menu (role_id, menu_id) WHERE is_deleted = 0;")
			.contains("uk_role_dept ON sys_role_dept (role_id, dept_id) WHERE is_deleted = 0;")
			.contains("uk_tenant_ds_code ON sys_tenant_datasource (tenant_code) WHERE is_deleted = 0;")
			.contains("CREATE UNIQUE INDEX IF NOT EXISTS uk_user_tenant_phone ON sys_user (tenant_id, phone)\n  WHERE is_deleted = 0 AND phone IS NOT NULL AND phone <> '';");
	}
}
