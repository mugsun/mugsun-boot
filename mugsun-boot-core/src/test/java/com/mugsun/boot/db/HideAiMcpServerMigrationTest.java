package com.mugsun.boot.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V4（下线 AI「对外暴露」= MCP Server 列表与开关）三套方言同号同义的静态检查；PG 上的实际执行见 {@link FlywayUpgradeFrom010Test}。
 * <p>只逻辑删除 ai:mcp:server 按钮及其角色授权：不删表、不动 ai_mcp_tool、不碰其它菜单。
 */
class HideAiMcpServerMigrationTest {

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"db/migration", "db/migration-og", "db/migration-dm"})
	@DisplayName("V4__hide_ai_mcp_server.sql 在 PG / openGauss / 达梦三个目录都存在，只逻辑删除 ai:mcp:server 及其授权")
	void v4ExistsInEveryDialect(String dir) throws Exception {
		String sql = read(dir + "/V4__hide_ai_mcp_server.sql");
		String code = sql.replaceAll("(?m)--.*$", "").toLowerCase();
		assertThat(code).contains("update sys_menu set is_deleted = 1")
			.contains("update sys_role_menu set is_deleted = 1")
			.contains("permission = 'ai:mcp:server'");
		assertThat(code).as("不删表、不删行、不改 ai_mcp_tool").doesNotContain("drop ").doesNotContain("delete ")
			.doesNotContain("truncate").doesNotContain("ai_mcp_tool").doesNotContain("alter ");
		if (dir.endsWith("-dm")) {
			assertThat(sql).as("达梦脚本手写，不能被 pg_to_dm.py 覆盖").contains("pg2dm: manual").doesNotContain("now()");
		}
	}

	private static String read(String path) throws Exception {
		try (InputStream in = HideAiMcpServerMigrationTest.class.getClassLoader().getResourceAsStream(path)) {
			assertThat(in).as(path).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
