package com.mugsun.boot.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BizTables.of 在配置 default-schema 时为业务表加 schema 前缀（信创库防 SYS_CATALOG）。
 */
class BizTablesTest {

	@AfterEach
	void resetSchema() throws Exception {
		Field schema = BizTables.class.getDeclaredField("schema");
		schema.setAccessible(true);
		schema.set(null, "");
	}

	@Test
	void ofPrefixesSysUserWhenDefaultSchemaConfigured() {
		BizTables tables = new BizTables();
		MockEnvironment env = new MockEnvironment();
		env.setProperty("mugsun.db.default-schema", "mugsun");
		tables.setEnvironment(env);
		assertThat(BizTables.of("sys_user")).isEqualTo("mugsun.sys_user");
	}

	@Test
	void ofReturnsBareTableWhenSchemaBlank() {
		BizTables tables = new BizTables();
		tables.setEnvironment(new MockEnvironment());
		assertThat(BizTables.of("sys_user")).isEqualTo("sys_user");
	}

	@Test
	void ofKeepsQualifiedTableUnchanged() {
		BizTables tables = new BizTables();
		MockEnvironment env = new MockEnvironment();
		env.setProperty("mugsun.db.default-schema", "mugsun");
		tables.setEnvironment(env);
		assertThat(BizTables.of("mugsun.sys_user")).isEqualTo("mugsun.sys_user");
	}
}
