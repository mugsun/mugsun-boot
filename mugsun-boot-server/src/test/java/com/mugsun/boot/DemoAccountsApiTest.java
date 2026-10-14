package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.auth.LoginLockService;
import com.mugsun.boot.auth.TwoFactorService;
import com.mugsun.boot.demo.DemoCapacityGate;
import com.mugsun.boot.demo.DemoConstants;
import com.mugsun.boot.demo.DemoSchemaGuard;
import com.mugsun.boot.demo.LogDataSourceRouter;
import com.mugsun.boot.mail.MailService;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.track.TrackEventStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 只读演示模式 B2 端到端：先另起 JVM 以 owner 账号跑一次 {@code --mugsun.demo.migrate-only=true}（迁移 + 启动期初始化），
 * 再执行 docs/demo/sql/demo-accounts.sql 建三个账号，然后用 demo profile + 三个低权账号启动应用：
 * 登录（含登录日志）、只读浏览、写请求 403、漏网写库 42501、埋点 upsert 降级、配额与容量闸门、关闭的组件。
 */
@ActiveProfiles("demo")
class DemoAccountsApiTest extends AbstractIntegrationTest {

	private static final String RO_PASSWORD = "demo-ro-test-pw";
	private static final String LOG_PASSWORD = "demo-log-test-pw";
	private static final String TRACK_PASSWORD = "demo-track-test-pw";
	private static boolean prepared;

	@DynamicPropertySource
	static void demoAccounts(DynamicPropertyRegistry registry) {
		prepare();
		registry.add("mybatis-flex.datasource.primary.username", () -> "mugsun_demo_ro");
		registry.add("mybatis-flex.datasource.primary.password", () -> RO_PASSWORD);
		registry.add("mybatis-flex.datasource.track.username", () -> "mugsun_demo_track");
		registry.add("mybatis-flex.datasource.track.password", () -> TRACK_PASSWORD);
		registry.add("mybatis-flex.datasource.log.url", AbstractIntegrationTest::primaryJdbcUrl);
		registry.add("mybatis-flex.datasource.log.username", () -> "mugsun_demo_log");
		registry.add("mybatis-flex.datasource.log.password", () -> LOG_PASSWORD);
		registry.add("mugsun.demo.track-collect", () -> "true");
	}

	/** owner 一次性迁移 + 建账号（同一 JVM 只做一次） */
	static synchronized void prepare() {
		if (prepared) {
			return;
		}
		// owner 一次性：另起一个 JVM 跑 --mugsun.demo.migrate-only=true（迁移 + 启动期初始化后 exit 0）。
		// 不能在本 JVM 再起第二个 Spring 上下文：warm-flow 的静态 SpringUtil 会串上下文（见 AbstractTrackIntegrationTest）；
		// 本类也因此在 pom 里单独一个 surefire execution（独立 fork）。
		java.util.List<String> cmd = new java.util.ArrayList<>(java.util.List.of(
			java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
			"-cp", System.getProperty("java.class.path"), "com.mugsun.boot.MugsunApplication"));
		containerProperties(new DynamicPropertyRegistry() {
			@Override
			public void add(String name, java.util.function.Supplier<Object> value) {
				cmd.add("--" + name + "=" + value.get());
			}
		});
		cmd.add("--server.port=0");
		cmd.add("--" + DemoConstants.MIGRATE_ONLY_PROPERTY + "=true");
		java.io.File log = new java.io.File("target/demo-migrate-only.log");
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log).start();
			if (!p.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)) {
				p.destroyForcibly();
				throw new IllegalStateException("migrate-only 超时，见 " + log.getAbsolutePath());
			}
			if (p.exitValue() != 0) {
				throw new IllegalStateException("migrate-only 退出码 " + p.exitValue() + "，见 " + log.getAbsolutePath());
			}
			if (count("SELECT COUNT(*) FROM sys_user WHERE username = ?", ADMIN_USERNAME) == 0) {
				throw new IllegalStateException("migrate-only 未播种管理员，见 " + log.getAbsolutePath());
			}
		} catch (java.io.IOException | SQLException e) {
			throw new IllegalStateException(e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
		String sql = readSql()
			.replace("{{RO_PASSWORD}}", RO_PASSWORD)
			.replace("{{LOG_PASSWORD}}", LOG_PASSWORD)
			.replace("{{TRACK_PASSWORD}}", TRACK_PASSWORD)
			.replace("{{BIZ_DB}}", dbName(primaryJdbcUrl()))
			.replace("{{TRACK_DB}}", TRACK_DB)
			.replace("{{OWNER}}", primaryJdbcUsername());
		int marker = sql.indexOf("-- @@TRACK_DB_SECTION@@");
		String biz = sql.substring(0, marker).replaceAll("(?m)^\\\\connect.*$", "");
		String track = sql.substring(marker);
		exec(primaryJdbcUrl(), primaryJdbcUsername(), primaryJdbcPassword(), biz);
		exec(jdbcUrlForDatabase(TRACK_DB), primaryJdbcUsername(), primaryJdbcPassword(), track);
		prepared = true;
	}

	private static String readSql() {
		try {
			return java.nio.file.Files.readString(java.nio.file.Path.of("..", "docs", "demo", "sql", "demo-accounts.sql"),
				StandardCharsets.UTF_8);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String dbName(String url) {
		String tail = url.substring(url.lastIndexOf('/') + 1);
		int q = tail.indexOf('?');
		return q < 0 ? tail : tail.substring(0, q);
	}

	private static void exec(String url, String user, String password, String sql) {
		try (Connection c = DriverManager.getConnection(url, user, password); Statement st = c.createStatement()) {
			st.execute(sql);
		} catch (SQLException e) {
			throw new IllegalStateException("执行建账号 SQL 失败：" + e.getMessage(), e);
		}
	}

	private static long count(String sql, Object... args) throws SQLException {
		try (Connection c = DriverManager.getConnection(primaryJdbcUrl(), primaryJdbcUsername(), primaryJdbcPassword());
			 var ps = c.prepareStatement(sql)) {
			for (int i = 0; i < args.length; i++) {
				ps.setObject(i + 1, args[i]);
			}
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		}
	}

	@Autowired
	private ApplicationContext context;
	@Autowired
	private Environment env;
	@Autowired
	private LogDataSourceRouter logRouter;
	@Autowired
	private LoginLockService loginLockService;
	@Autowired
	private TwoFactorService twoFactorService;
	@Autowired
	private MailService mailService;
	@Autowired
	private TrackEventStore trackEventStore;
	@Autowired
	private DemoCapacityGate capacityGate;

	@Test
	void demoContextUsesLowPrivilegeAccounts() {
		assertThat(env.getProperty("mybatis-flex.datasource.primary.username")).isEqualTo("mugsun_demo_ro");
		assertThat(env.getProperty("spring.flyway.enabled")).isEqualTo("false");
		assertThat(env.getProperty("spring.data.redis.database")).as("demo 用独立 Redis db").isEqualTo("4");
		assertThat(context.getBeansOfType(DemoSchemaGuard.class)).hasSize(1);
		assertThat(logRouter.routed()).isTrue();
		// 启动期写入和写库任务都不装配
		for (String name : List.of("dataInitializer", "loginLogSchemaRunner", "logCleanJob", "notifyRetryJob",
			"trackPartitionJob", "trackStats5mJob", "trackSessionSettleJob", "trackEventCleanJob")) {
			assertThat(context.containsBean(name)).as(name + " 在 demo 下不应装配").isFalse();
		}
		// AI 模块关闭
		assertThat(context.getBeanNamesForType(com.mugsun.boot.ai.AiAutoConfiguration.class)).isEmpty();
	}

	@Test
	void loginWorksAndWritesLoginLogThroughLogAccount() throws Exception {
		LocalDateTime before = LocalDateTime.now().minusSeconds(5);
		String token = loginAdmin();
		assertThat(count("SELECT COUNT(*) FROM sys_login_log WHERE username = ? AND status = 1 AND login_time >= ?",
			ADMIN_USERNAME, before)).as("登录日志经日志账号写入").isGreaterThanOrEqualTo(1);

		ResponseEntity<String> list = get("/system/user/list?current=1&size=5", token);
		assertThat(list.getStatusCode().value()).isEqualTo(200);
		assertThat(readBody(list).path("code").asInt()).as("只读账号可以查询").isEqualTo(200);

		ResponseEntity<String> write = post("/system/user/submit", Map.of("username", "demo-should-fail"), token);
		assertThat(write.getStatusCode().value()).isEqualTo(403);
		assertThat(readBody(write).path("errorCode").asText()).isEqualTo("DEMO_READONLY");
	}

	@Test
	void readOnlyAccountReallyCannotWrite() {
		assertThatThrownBy(() -> {
			try (Connection c = DriverManager.getConnection(primaryJdbcUrl(), "mugsun_demo_ro", RO_PASSWORD);
				 Statement st = c.createStatement()) {
				st.executeUpdate("UPDATE sys_param SET param_value = param_value WHERE 1 = 0");
			}
		}).isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("42501");
		assertThatThrownBy(() -> {
			try (Connection c = DriverManager.getConnection(primaryJdbcUrl(), "mugsun_demo_log", LOG_PASSWORD);
				 Statement st = c.createStatement()) {
				st.executeQuery("SELECT COUNT(*) FROM sys_user");
			}
		}).isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("42501");
	}

	@Test
	void schemaGuardRejectsPendingMigrations() {
		Flyway pending = Flyway.configure()
			.dataSource(primaryJdbcUrl(), "mugsun_demo_ro", RO_PASSWORD)
			.locations("classpath:db/migration", "classpath:db/demo-pending")
			.baselineVersion("0")
			.placeholderReplacement(false)
			.load();
		assertThatThrownBy(() -> DemoSchemaGuard.validate(pending, "业务库"))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("拒绝启动");
	}

	@Test
	void notificationsTwoFactorAndLockAreDemoSafe() {
		assertThat(twoFactorService.isEnabled()).isFalse();
		mailService.send("someone@example.com", "x", "y");
		assertThat(mailService.sendByTemplate("someone@example.com", "any", Map.of())).contains("演示环境");
		assertThat(loginLockService.keyForLogin("000000", "admin", "203.0.113.9")).endsWith("@ip:203.0.113.9");
	}

	@Test
	void trackUpsertsDegradeToInsertOnly() throws Exception {
		String sid = "demo-sess-" + System.nanoTime();
		LocalDateTime now = LocalDateTime.now();
		TrackEventStore.SessionAggregate agg = new TrackEventStore.SessionAggregate(sid, "demo-app", PLATFORM_TENANT,
			"d1", null, now, now, 0, 1, 1, "/a", "/a", null, null, null, null, null, null, 0, 0);
		TenantContext.ignore(() -> {
			trackEventStore.upsertSessions(List.of(agg));
			trackEventStore.upsertSessions(List.of(agg));
			return null;
		});
		try (Connection c = DriverManager.getConnection(jdbcUrlForDatabase(TRACK_DB), primaryJdbcUsername(), primaryJdbcPassword());
			 var ps = c.prepareStatement("SELECT event_count FROM track_session WHERE session_id = ?")) {
			ps.setString(1, sid);
			try (ResultSet rs = ps.executeQuery()) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getInt(1)).as("DO NOTHING：第二批不累加").isEqualTo(1);
			}
		}
	}

	@Test
	void trackCapacityGate() {
		capacityGate.updateTrack(1L, true);
		try {
			ResponseEntity<String> r = postText("/track/collect?app_key=cap-probe", "", null);
			assertThat(r.getStatusCode().value()).isEqualTo(503);
			JsonNode body = readBody(r);
			assertThat(body.path("errorCode").asText()).isEqualTo("DEMO_CAPACITY");
		} finally {
			capacityGate.updateTrack(0L, false);
		}
	}

	@Test
	void socialAndAiPrefixesBlocked() {
		assertThat(get("/auth/social/render/gitee", null).getStatusCode().value()).isEqualTo(403);
		assertThat(get("/system/ai/model/list", null).getStatusCode().value()).isEqualTo(403);
	}
}
