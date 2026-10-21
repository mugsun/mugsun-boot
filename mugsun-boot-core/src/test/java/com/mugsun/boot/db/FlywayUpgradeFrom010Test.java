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
			}
			Flyway current = current(url);
			current.migrate();
			current.validate();
			assertUpgraded(url, true);
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

	/**
	 * V7：删除后的行不再占唯一键（与 openGauss / 达梦 V7 同一套场景 SQL，见 db/unique-delete-flag/）。
	 * 删租户后再建同编号、同一个键删两次、手机号 NULL / 空串可重复；未删除的行仍唯一。
	 */
	@Test
	void deletedRowsDoNotBlockUniqueKeysAfterV7() throws Exception {
		withTempDb(url -> {
			Flyway target3 = Flyway.configure().dataSource(url, user, pass)
				.locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("0")
				.placeholderReplacement(false).target("3").load();
			target3.migrate();
			try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
				executeScript(st, "db/unique-delete-flag/scenario.sql");
			}
			// 停在 V10：V11 把租户编号改回全量唯一，verify.sql 里「删租户后再建同编号」只在 V7–V10 成立
			Flyway upToV10 = Flyway.configure().dataSource(url, user, pass)
				.locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("0")
				.placeholderReplacement(false).target("10").load();
			upToV10.migrate();
			upToV10.validate();
			try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
				executeScript(st, "db/unique-delete-flag/verify.sql");
				assertThat(count(st, "SELECT count(*) FROM sys_tenant WHERE tenant_code = '700001'")).isEqualTo(3);
				assertThat(count(st, "SELECT count(*) FROM sys_user WHERE username = 'u1'")).isEqualTo(3);
				assertThat(count(st, "SELECT count(*) FROM sys_role_menu WHERE role_id = 9100000000000000031")).isEqualTo(3);
				int n = 0;
				for (String sql : statements(readResource("db/unique-delete-flag/negative.sql"))) {
					n++;
					java.sql.SQLException e = org.assertj.core.api.Assertions.catchThrowableOfType(
						java.sql.SQLException.class, () -> st.execute(sql));
					assertThat((Object) e).as("第 %d 条应撞唯一键：%s", n, sql).isNotNull();
					assertThat(e.getSQLState()).as(sql).isEqualTo("23505");
				}
				assertThat(n).isEqualTo(8);
			}
		});
	}

	/**
	 * V11：租户编号改回全量唯一（含已删除行），删掉的编号不能再用；其余 V7 口径不变。
	 */
	@Test
	void deletedTenantCodeCannotBeReusedAfterV11() throws Exception {
		withTempDb(url -> {
			Flyway current = current(url);
			current.migrate();
			current.validate();
			try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
				st.execute("INSERT INTO sys_tenant (id, tenant_code, tenant_name, is_deleted) VALUES (9100000000000000301, '700301', 'T', 0)");
				st.execute("UPDATE sys_tenant SET is_deleted = 1 WHERE id = 9100000000000000301");
				java.sql.SQLException e = org.assertj.core.api.Assertions.catchThrowableOfType(java.sql.SQLException.class, () -> st.execute(
					"INSERT INTO sys_tenant (id, tenant_code, tenant_name, is_deleted) VALUES (9100000000000000302, '700301', 'T2', 0)"));
				assertThat((Object) e).as("已删除租户的编号不能复用").isNotNull();
				assertThat(e.getSQLState()).isEqualTo("23505");
				st.execute("INSERT INTO sys_tenant_datasource (id, tenant_code, ds_url, ds_username, ds_password, is_deleted) VALUES (9100000000000000311, '700301', 'u', 'u', 'p', 1)");
				e = org.assertj.core.api.Assertions.catchThrowableOfType(java.sql.SQLException.class, () -> st.execute(
					"INSERT INTO sys_tenant_datasource (id, tenant_code, ds_url, ds_username, ds_password, is_deleted) VALUES (9100000000000000312, '700301', 'u', 'u', 'p', 0)"));
				assertThat((Object) e).as("已删除数据源的租户编号不能复用").isNotNull();
				assertThat(e.getSQLState()).isEqualTo("23505");
			}
		});
	}

	/** V11 遇到已被复用的编号就中止，不动索引 */
	@Test
	void v11AbortsWhenTenantCodeAlreadyReused() throws Exception {
		withTempDb(url -> {
			Flyway.configure().dataSource(url, user, pass)
				.locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("0")
				.placeholderReplacement(false).target("10").load().migrate();
			try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
				st.execute("INSERT INTO sys_tenant (id, tenant_code, tenant_name, is_deleted) VALUES (9100000000000000321, '700321', 'old', 1)");
				st.execute("INSERT INTO sys_tenant (id, tenant_code, tenant_name, is_deleted) VALUES (9100000000000000322, '700321', 'new', 0)");
			}
			Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> current(url).migrate());
			assertThat(t).isNotNull();
			assertThat(rootMessage(t)).contains("V11 升级中止");
			try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
				assertThat(count(st, "SELECT count(*) FROM pg_indexes WHERE indexname = 'uk_tenant_code' AND indexdef LIKE '%is_deleted%'"))
					.as("部分唯一索引原样未动").isEqualTo(1);
			}
		});
	}

	private static String rootMessage(Throwable t) {
		StringBuilder sb = new StringBuilder();
		for (Throwable x = t; x != null; x = x.getCause()) {
			sb.append(x.getMessage()).append('\n');
		}
		return sb.toString();
	}

	private static void executeScript(Statement st, String path) throws Exception {
		for (String sql : statements(readResource(path))) {
			st.execute(sql);
		}
	}

	/** 按行尾分号拆语句，去掉整行注释 */
	private static java.util.List<String> statements(String script) {
		java.util.List<String> out = new java.util.ArrayList<>();
		for (String part : script.split(";\\s*\\n")) {
			String sql = part.lines().filter(l -> !l.trim().startsWith("--"))
				.collect(java.util.stream.Collectors.joining("\n")).trim();
			if (sql.endsWith(";")) {
				sql = sql.substring(0, sql.length() - 1);
			}
			if (!sql.isEmpty()) {
				out.add(sql);
			}
		}
		return out;
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
			assertThat(count(st, "SELECT max(version::int) FROM flyway_schema_history WHERE success AND version ~ '^[0-9]+$'")).isGreaterThanOrEqualTo(3);
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
