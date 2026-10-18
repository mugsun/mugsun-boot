package com.mugsun.boot.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 升级回归：0.1.0 发布版 V1 建的库，升级到当前迁移必须 migrate + validate 通过（不得校验和不匹配）。
 * <p>每个用例独立建一个临时库并在结束后删除；本地用 Testcontainers，CI（MUGSUN_TEST_USE_SERVICES）用服务容器里的临时库。
 */
class FlywayUpgradeFrom010Test {

	private static final String RELEASED_V1 = "db/released/v0.1.0/V1__baseline_0_1.sql";
	private static final String CURRENT_V1 = "db/migration/V1__baseline_0_1.sql";

	private static PostgreSQLContainer<?> container;
	private static String adminUrl;
	private static String user;
	private static String pass;

	@BeforeAll
	static void start() {
		String flag = System.getenv("MUGSUN_TEST_USE_SERVICES");
		if (flag != null && ("true".equalsIgnoreCase(flag) || "1".equals(flag))) {
			adminUrl = "jdbc:postgresql://" + env("POSTGRES_HOST", "127.0.0.1") + ":" + env("POSTGRES_PORT", "5432")
				+ "/" + env("POSTGRES_DB", "mugsun");
			user = env("POSTGRES_USER", "mugsun");
			pass = env("POSTGRES_PASSWORD", "mugsun");
		} else {
			container = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
				.withDatabaseName("mugsun").withUsername("mugsun").withPassword("mugsun");
			container.start();
			adminUrl = container.getJdbcUrl();
			user = container.getUsername();
			pass = container.getPassword();
		}
	}

	@AfterAll
	static void stop() {
		if (container != null) {
			container.stop();
		}
	}

	private static String env(String k, String d) {
		String v = System.getenv(k);
		return v == null || v.isBlank() ? d : v;
	}

	@Test
	void currentV1IsByteIdenticalToReleased() throws Exception {
		assertThat(sha256(CURRENT_V1)).isEqualTo(sha256(RELEASED_V1));
	}

	@Test
	void upgradeFrom010PassesMigrateAndValidate() throws Exception {
		withTempDb(url -> {
			installReleased010(url);
			try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
				// 0.1.0 的库里还没有 B4 列，有假更新日志
				assertThat(count(st, "SELECT count(*) FROM information_schema.columns WHERE table_name='sys_attach' AND column_name='create_user'")).isZero();
				assertThat(count(st, "SELECT count(*) FROM sys_changelog WHERE version IN ('v1.2.0','v1.1.0','v1.0.0')")).isEqualTo(3);
				// 模拟升级前已有管理员角色，验证 V2 会补授权
				st.execute("INSERT INTO sys_role (id, role_name, role_code, is_deleted) VALUES (9100000000000000001, '管理员', 'admin', 0)");
				// 0.1.0 里「对外暴露」按钮（ai:mcp:server）仍在，且有角色授过它；V4 要把两者都逻辑删除
				assertThat(count(st, "SELECT count(*) FROM sys_menu WHERE permission = 'ai:mcp:server' AND is_deleted = 0")).isEqualTo(1);
				st.execute("INSERT INTO sys_role_menu (id, role_id, menu_id, create_time, is_deleted) "
					+ "SELECT 9100000000000000226, 9100000000000000001, id, now(), 0 FROM sys_menu WHERE permission = 'ai:mcp:server'");
			}
			Flyway current = current(url);
			current.migrate();
			current.validate();
			assertUpgraded(url, true);
			try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
				assertThat(count(st, "SELECT count(*) FROM sys_role_menu WHERE id = 9100000000000000226 AND is_deleted = 1")).isEqualTo(1);
			}
		});
	}

	/** 曾跑过「带 B4 的 V1」并 repair 过的开发库：列/权限/授权已存在，新 V2/V3 仍须成功（幂等）。 */
	@Test
	void devDbThatRanPatchedV1StillMigrates() throws Exception {
		withTempDb(url -> {
			installReleased010(url);
			try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
				st.execute("INSERT INTO sys_role (id, role_name, role_code, is_deleted) VALUES (9100000000000000001, '管理员', 'admin', 0)");
				st.execute(readResource("db/migration/V2__file_perm.sql"));
				// 改用另一个 id 授过同一权限，V2 不应重复授
				st.execute("DELETE FROM sys_role_menu WHERE id = 1077000000000000101");
				st.execute("INSERT INTO sys_role_menu (id, role_id, menu_id, create_time, is_deleted) VALUES (9100000000000000101, 9100000000000000001, 1077000000000000001, now(), 0)");
			}
			Flyway current = current(url);
			current.migrate();
			current.validate();
			assertUpgraded(url, true);
			try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
				assertThat(count(st, "SELECT count(*) FROM sys_role_menu WHERE menu_id = 1077000000000000001")).isEqualTo(1);
			}
		});
	}

	@Test
	void freshInstallMigratesAndValidates() throws Exception {
		withTempDb(url -> {
			Flyway current = current(url);
			current.migrate();
			current.validate();
			assertUpgraded(url, false);
		});
	}

	private void installReleased010(String url) {
		Flyway.configure().dataSource(url, user, pass)
			.locations("classpath:db/released/v0.1.0")
			.placeholderReplacement(false)
			.load().migrate();
	}

	private Flyway current(String url) {
		// 与 application.yml 的 spring.flyway 配置一致
		return Flyway.configure().dataSource(url, user, pass)
			.locations("classpath:db/migration")
			.baselineOnMigrate(true).baselineVersion("0")
			.placeholderReplacement(false)
			.load();
	}

	private void assertUpgraded(String url, boolean expectAdminGrant) throws Exception {
		try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
			assertThat(count(st, "SELECT count(*) FROM information_schema.columns WHERE table_name='sys_attach' AND column_name='create_user'")).isEqualTo(1);
			assertThat(count(st, "SELECT count(*) FROM sys_menu WHERE permission IN ('sys:file:manage','sys:file:public','sys:file:remove') AND is_deleted=0")).isEqualTo(3);
			assertThat(count(st, "SELECT count(*) FROM sys_changelog WHERE version IN ('v1.2.0','v1.1.0','v1.0.0')")).isZero();
			assertThat(count(st, "SELECT count(*) FROM sys_changelog WHERE version = '0.1.0'")).isEqualTo(1);
			if (expectAdminGrant) {
				assertThat(count(st, "SELECT count(DISTINCT rm.menu_id) FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id "
					+ "WHERE r.role_code='admin' AND rm.menu_id IN (1077000000000000001,1077000000000000002,1077000000000000003)")).isEqualTo(3);
			}
			// V4：下线 AI「对外暴露」（MCP Server 列表与开关）；页面「MCP 工具箱」与其它按钮、ai_mcp_tool 表都保留
			assertThat(count(st, "SELECT count(*) FROM sys_menu WHERE permission = 'ai:mcp:server' AND is_deleted = 0")).isZero();
			assertThat(count(st, "SELECT count(*) FROM sys_role_menu rm JOIN sys_menu m ON m.id = rm.menu_id "
				+ "WHERE m.permission = 'ai:mcp:server' AND rm.is_deleted = 0")).isZero();
			assertThat(count(st, "SELECT count(*) FROM sys_menu WHERE permission IN ('ai:mcp:list','ai:mcp:save','ai:mcp:debug') AND is_deleted = 0")).isEqualTo(3);
			assertThat(count(st, "SELECT count(*) FROM information_schema.tables WHERE table_name = 'ai_mcp_tool'")).isEqualTo(1);
			assertThat(count(st, "SELECT max(version::int) FROM flyway_schema_history WHERE success AND version ~ '^[0-9]+$'")).isGreaterThanOrEqualTo(4);
		}
	}

	private interface DbBody {
		void run(String url) throws Exception;
	}

	private void withTempDb(DbBody body) throws Exception {
		String db = "mugsun_upg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		try (Connection c = DriverManager.getConnection(adminUrl, user, pass); Statement st = c.createStatement()) {
			st.execute("CREATE DATABASE " + db);
		}
		try {
			body.run(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + db);
		} finally {
			try (Connection c = DriverManager.getConnection(adminUrl, user, pass); Statement st = c.createStatement()) {
				st.execute("DROP DATABASE IF EXISTS " + db + " WITH (FORCE)");
			}
		}
	}

	private static long count(Statement st, String sql) throws Exception {
		try (ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	private static String readResource(String path) throws Exception {
		try (InputStream in = FlywayUpgradeFrom010Test.class.getClassLoader().getResourceAsStream(path)) {
			assertThat(in).as(path).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static String sha256(String path) throws Exception {
		try (InputStream in = FlywayUpgradeFrom010Test.class.getClassLoader().getResourceAsStream(path)) {
			assertThat(in).as(path).isNotNull();
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
		}
	}
}
