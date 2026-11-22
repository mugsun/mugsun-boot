package com.mugsun.boot.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 全新安装：空库跑完当前迁移（基线 + V17）要 migrate、validate 都通过，建出来的库还要守住几条约定：
 * 删除后的行不占唯一键、租户编号不能复用、表单标识租户内唯一、内置角色和参数齐全。
 * <p>整个类共用一个临时库，结束后删除；各用例用的 id 和编号互不相同。本地用 Testcontainers，
 * CI（MUGSUN_TEST_USE_SERVICES）在服务容器里建临时库。
 */
class FlywayFreshInstallTest {

	private static PostgreSQLContainer container;
	private static String adminUrl;
	private static String user;
	private static String pass;
	private static String db;
	private static String url;
	private static Flyway flyway;

	@BeforeAll
	static void migrateFreshDb() throws Exception {
		String flag = System.getenv("MUGSUN_TEST_USE_SERVICES");
		if (flag != null && ("true".equalsIgnoreCase(flag) || "1".equals(flag))) {
			adminUrl = "jdbc:postgresql://" + env("POSTGRES_HOST", "127.0.0.1") + ":" + env("POSTGRES_PORT", "5432")
				+ "/" + env("POSTGRES_DB", "mugsun");
			user = env("POSTGRES_USER", "mugsun");
			pass = env("POSTGRES_PASSWORD", "mugsun");
		} else {
			container = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
				.withDatabaseName("mugsun").withUsername("mugsun").withPassword("mugsun");
			container.start();
			adminUrl = container.getJdbcUrl();
			user = container.getUsername();
			pass = container.getPassword();
		}
		String name = "mugsun_fresh_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		try (Connection c = DriverManager.getConnection(adminUrl, user, pass); Statement st = c.createStatement()) {
			st.execute("CREATE DATABASE " + name);
		}
		db = name;
		url = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + db;
		// 与 application.yml + FlywayPgCompatConfig 一致（V17 CONCURRENTLY 须会话级锁）
		flyway = Flyway.configure().dataSource(url, user, pass)
			.locations("classpath:db/migration")
			.baselineOnMigrate(true).baselineVersion("0")
			.placeholderReplacement(false)
			.configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
			.load();
		flyway.migrate();
	}

	@AfterAll
	static void dropFreshDb() throws Exception {
		try {
			if (db != null) {
				try (Connection c = DriverManager.getConnection(adminUrl, user, pass); Statement st = c.createStatement()) {
					st.execute("DROP DATABASE IF EXISTS " + db + " WITH (FORCE)");
				}
			}
		} finally {
			if (container != null) {
				container.stop();
			}
		}
	}

	@Test
	void migratesAndValidates() throws Exception {
		flyway.validate();
		assertThat(flyway.info().pending()).as("没有漏跑的迁移").isEmpty();
		inDb(st -> {
			assertThat(count(st, "SELECT count(*) FROM flyway_schema_history WHERE success AND script = '" + MigrationBaseline.MAIN + "'"))
				.as("基线记进迁移历史").isEqualTo(1);
			assertThat(count(st, "SELECT count(*) FROM flyway_schema_history WHERE NOT success")).isZero();
			// 附件归属列、三个附件权限、真实的 0.1.0 更新日志（基线里原 V2、V3 两段）
			assertThat(count(st, "SELECT count(*) FROM information_schema.columns WHERE table_name='sys_attach' AND column_name='create_user'")).isEqualTo(1);
			assertThat(count(st, "SELECT count(*) FROM sys_menu WHERE permission IN ('sys:file:manage','sys:file:public','sys:file:remove') AND is_deleted=0")).isEqualTo(3);
			assertThat(count(st, "SELECT count(*) FROM sys_changelog WHERE version IN ('v1.2.0','v1.1.0','v1.0.0')")).isZero();
			assertThat(count(st, "SELECT count(*) FROM sys_changelog WHERE version = '0.1.0'")).isEqualTo(1);
			// 用户导出、导入各自的权限码（原 V13）
			assertThat(count(st, "SELECT count(*) FROM sys_menu WHERE permission IN ('sys:user:export','sys:user:import')")).isEqualTo(2);
		});
	}

	/** 内置的 user 角色带 sys:file:upload，注册角色参数指向它（基线里原 V14 那一段） */
	@Test
	void seedsUserRoleWithUpload() throws Exception {
		inDb(st -> {
			String sql = "SELECT count(*) FROM sys_role r JOIN sys_role_menu rm ON rm.role_id = r.id AND rm.is_deleted = 0 "
				+ "JOIN sys_menu m ON m.id = rm.menu_id WHERE r.role_code = 'user' AND r.tenant_id = '000000' "
				+ "AND m.permission = 'sys:file:upload'";
			assertThat(count(st, sql)).isEqualTo(1);
			assertThat(count(st, "SELECT count(*) FROM sys_param WHERE param_key = 'security.register.register-role-code' AND param_value = 'user'")).isEqualTo(1);
			assertThat(count(st, "SELECT count(*) FROM sys_role WHERE role_code = 'user' AND tenant_id = '000000'")).isEqualTo(1);
		});
	}

	/** 埋点回放和响应体的三个存储容量参数带默认值落库（基线里原 V22 那一段） */
	@Test
	void seedsTrackStorageQuotaParams() throws Exception {
		inDb(st -> {
			String one = "SELECT count(*) FROM sys_param WHERE param_key = '%s' AND param_value = '%s' AND is_deleted = 0";
			assertThat(count(st, one.formatted("track.storage.daily-max-bytes", "10737418240"))).isEqualTo(1);
			assertThat(count(st, one.formatted("track.storage.app-daily-max-bytes", "2147483648"))).isEqualTo(1);
			assertThat(count(st, one.formatted("track.storage.min-free-bytes", "5368709120"))).isEqualTo(1);
			assertThat(count(st, "SELECT count(*) FROM sys_param WHERE param_key LIKE 'track.storage.%'")).isEqualTo(3);
		});
	}

	/** 租户编号是全量唯一（含已删除的行），删掉的编号不能再用；数据源编号只约束未删除的行，可以删后重建 */
	@Test
	void deletedTenantCodeCannotBeReused() throws Exception {
		inDb(st -> {
			st.execute("INSERT INTO sys_tenant (id, tenant_code, tenant_name, is_deleted) VALUES (9100000000000000301, '700301', 'T', 0)");
			st.execute("UPDATE sys_tenant SET is_deleted = 1 WHERE id = 9100000000000000301");
			SQLException e = catchThrowableOfType(SQLException.class, () -> st.execute(
				"INSERT INTO sys_tenant (id, tenant_code, tenant_name, is_deleted) VALUES (9100000000000000302, '700301', 'T2', 0)"));
			assertThat((Object) e).as("已删除租户的编号不能复用").isNotNull();
			assertThat(e.getSQLState()).isEqualTo("23505");
			st.execute("INSERT INTO sys_tenant_datasource (id, tenant_code, ds_url, ds_username, ds_password, is_deleted) VALUES (9100000000000000311, '700301', 'u', 'u', 'p', 0)");
			st.execute("UPDATE sys_tenant_datasource SET is_deleted = 1 WHERE id = 9100000000000000311");
			st.execute("INSERT INTO sys_tenant_datasource (id, tenant_code, ds_url, ds_username, ds_password, is_deleted) VALUES (9100000000000000312, '700301', 'u', 'u', 'p', 0)");
			assertThat(count(st, "SELECT count(*) FROM sys_tenant_datasource WHERE tenant_code = '700301'")).isEqualTo(2);
		});
	}

	/**
	 * 删除后的行不占唯一键：同一个键删两次后还能再建，手机号 NULL / 空串可以重复；未删除的行仍然唯一。
	 * 场景 SQL 在 db/unique-delete-flag/，openGauss、达梦手工验证用的是同一套。
	 */
	@Test
	void softDeletedRowsDoNotBlockUniqueKeys() throws Exception {
		inDb(st -> {
			executeScript(st, "db/unique-delete-flag/scenario.sql");
			executeScript(st, "db/unique-delete-flag/verify.sql");
			assertThat(count(st, "SELECT count(*) FROM sys_user WHERE username = 'u1'")).isEqualTo(3);
			assertThat(count(st, "SELECT count(*) FROM sys_role_menu WHERE role_id = 9100000000000000031")).isEqualTo(3);
			assertThat(count(st, "SELECT count(*) FROM sys_tenant_datasource WHERE tenant_code = 't1'")).isEqualTo(3);
			int n = 0;
			for (String sql : statements(readResource("db/unique-delete-flag/negative.sql"))) {
				n++;
				SQLException e = catchThrowableOfType(SQLException.class, () -> st.execute(sql));
				assertThat((Object) e).as("第 %d 条应撞唯一键：%s", n, sql).isNotNull();
				assertThat(e.getSQLState()).as(sql).isEqualTo("23505");
			}
			assertThat(n).isEqualTo(8);
		});
	}

	/** 表单标识在租户内唯一：别的租户可以用同一个标识，已删除的表单不占标识；全局唯一的旧索引不存在 */
	@Test
	void formKeyIsUniquePerTenantAmongLiveRows() throws Exception {
		inDb(st -> {
			assertThat(count(st, "SELECT count(*) FROM pg_indexes WHERE indexname = 'uk_form_key'")).as("全局唯一的旧索引已删").isZero();
			String insert = "INSERT INTO sys_form (id, name, form_key, status, is_deleted, tenant_id) VALUES (%d, 'x', 'it_form_leave', 1, 0, '%s')";
			st.execute(insert.formatted(9100000000000002101L, "000000"));
			st.execute(insert.formatted(9100000000000002102L, "700021"));
			SQLException dup = catchThrowableOfType(SQLException.class,
				() -> st.execute(insert.formatted(9100000000000002103L, "700021")));
			assertThat((Object) dup).as("同一租户里标识不能重复").isNotNull();
			assertThat(dup.getSQLState()).isEqualTo("23505");
			st.execute("UPDATE sys_form SET is_deleted = 1 WHERE id = 9100000000000002102");
			st.execute(insert.formatted(9100000000000002103L, "700021"));
			assertThat(count(st, "SELECT count(*) FROM sys_form WHERE form_key = 'it_form_leave'")).isEqualTo(3);
		});
	}

	private interface DbBody {
		void run(Statement st) throws Exception;
	}

	private static void inDb(DbBody body) throws Exception {
		try (Connection c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) {
			body.run(st);
		}
	}

	private static String env(String k, String d) {
		String v = System.getenv(k);
		return v == null || v.isBlank() ? d : v;
	}

	private static void executeScript(Statement st, String path) throws Exception {
		for (String sql : statements(readResource(path))) {
			st.execute(sql);
		}
	}

	/** 按行尾分号拆语句，去掉整行注释 */
	private static List<String> statements(String script) {
		List<String> out = new ArrayList<>();
		for (String part : script.split(";\\s*\\n")) {
			String sql = part.lines().filter(l -> !l.trim().startsWith("--")).collect(Collectors.joining("\n")).trim();
			if (sql.endsWith(";")) {
				sql = sql.substring(0, sql.length() - 1);
			}
			if (!sql.isEmpty()) {
				out.add(sql);
			}
		}
		return out;
	}

	private static long count(Statement st, String sql) throws Exception {
		try (ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	private static String readResource(String path) throws Exception {
		try (InputStream in = FlywayFreshInstallTest.class.getClassLoader().getResourceAsStream(path)) {
			assertThat(in).as(path).isNotNull();
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
