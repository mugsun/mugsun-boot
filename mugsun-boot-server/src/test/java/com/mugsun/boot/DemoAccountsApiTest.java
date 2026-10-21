package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.auth.LoginLockService;
import com.mugsun.boot.auth.TwoFactorService;
import com.mugsun.boot.demo.DemoCapacityGate;
import com.mugsun.boot.demo.DemoConstants;
import com.mugsun.boot.demo.DemoRateLimiter;
import com.mugsun.boot.demo.DemoSchemaGuard;
import com.mugsun.boot.demo.LogDataSourceRouter;
import com.mugsun.boot.log.OperationLogService;
import com.mugsun.boot.log.entity.SysOperLog;
import com.mugsun.boot.log.mapper.SysOperLogMapper;
import com.mugsun.boot.system.service.ParamService;
import com.mybatisflex.core.FlexGlobalConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.transaction.PlatformTransactionManager;
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

	static {
		// 低权限账号覆盖父类的 owner 账号（见 AbstractIntegrationTest.PROPERTY_OVERRIDES；本类在独立 fork 里跑）
		PROPERTY_OVERRIDES.put("mybatis-flex.datasource.primary.username", () -> "mugsun_demo_ro");
		PROPERTY_OVERRIDES.put("mybatis-flex.datasource.primary.password", () -> RO_PASSWORD);
		PROPERTY_OVERRIDES.put("mybatis-flex.datasource.track.username", () -> "mugsun_demo_track");
		PROPERTY_OVERRIDES.put("mybatis-flex.datasource.track.password", () -> TRACK_PASSWORD);
		PROPERTY_OVERRIDES.put("mybatis-flex.datasource.log.url", AbstractIntegrationTest::primaryJdbcUrl);
		PROPERTY_OVERRIDES.put("mybatis-flex.datasource.log.username", () -> "mugsun_demo_log");
		PROPERTY_OVERRIDES.put("mybatis-flex.datasource.log.password", () -> LOG_PASSWORD);
		PROPERTY_OVERRIDES.put("mugsun.demo.track-collect", () -> "true");
	}

	/** 先以 owner 迁移、建账号，再让 Spring 用低权限账号起 demo 上下文 */
	@DynamicPropertySource
	static void demoAccounts(DynamicPropertyRegistry registry) {
		prepare();
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
		// owner 进程用父类的默认（owner）账号：临时摘掉低权限覆盖项
		Map<String, java.util.function.Supplier<Object>> overrides = new java.util.LinkedHashMap<>(PROPERTY_OVERRIDES);
		PROPERTY_OVERRIDES.clear();
		try {
			AbstractIntegrationTest.containerProperties(new DynamicPropertyRegistry() {
				@Override
				public void add(String name, java.util.function.Supplier<Object> value) {
					cmd.add("--" + name + "=" + value.get());
				}
			});
		} finally {
			PROPERTY_OVERRIDES.putAll(overrides);
		}
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
	private DemoRateLimiter rateLimiter;
	@Autowired
	private TwoFactorService twoFactorService;
	@Autowired
	private MailService mailService;
	@Autowired
	private TrackEventStore trackEventStore;
	@Autowired
	private DemoCapacityGate capacityGate;

	@Autowired
	private OperationLogService operationLogService;
	@Autowired
	private SysOperLogMapper operLogMapper;
	@Autowired
	private ParamService paramService;
	@Autowired
	private PlatformTransactionManager txManager;

	@Test
	void demoPoolsAreSizedForThirtyConnections() {
		var flex = FlexGlobalConfig.getDefaultConfig().getDataSource();
		Map<String, Integer> expected = Map.of("primary", 5, "log", 4);
		expected.forEach((key, max) -> {
			javax.sql.DataSource ds = flex.getDataSourceMap().get(key);
			assertThat(ds).as(key).isInstanceOf(HikariDataSource.class);
			assertThat(((HikariDataSource) ds).getMaximumPoolSize()).as(key + " maximum-pool-size 生效").isEqualTo(max);
			assertThat(((HikariDataSource) ds).getMinimumIdle()).as(key + " minimum-idle 生效").isEqualTo(1);
		});
		javax.sql.DataSource track = flex.getDataSourceMap().get("track");
		if (track != null) {
			assertThat(((HikariDataSource) track).getMaximumPoolSize()).as("track maximum-pool-size 生效").isEqualTo(4);
		}
	}

	private static SysOperLog chainEntry(int n) {
		SysOperLog l = new SysOperLog();
		l.setTitle("demo-chain");
		l.setRequestMethod("POST");
		l.setRequestUri("/it/demo-chain/" + n);
		l.setOperator("1");
		l.setStatus(1);
		return l;
	}

	@Test
	void concurrentOperLogAppendsThroughLogAccountKeepOneChain() throws Exception {
		// 两个实例（各自的本地锁）模拟两个节点：只能靠日志连接上的链头行锁串行
		OperationLogService other = new OperationLogService(operLogMapper, paramService, txManager, logRouter, 64);
		long before = count("SELECT COUNT(*) FROM sys_oper_log WHERE request_uri LIKE '/it/demo-chain/%'");
		int n = 24;
		java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
		for (int i = 0; i < n; i++) {
			OperationLogService target = i % 2 == 0 ? operationLogService : other;
			int k = i;
			Thread.ofVirtual().start(() -> {
				try {
					go.await();
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
				TenantContext.execute(PLATFORM_TENANT, () -> target.saveAsync(chainEntry(k)));
			});
		}
		go.countDown();
		long deadline = System.currentTimeMillis() + 60_000;
		while (count("SELECT COUNT(*) FROM sys_oper_log WHERE request_uri LIKE '/it/demo-chain/%'") < before + n
			&& System.currentTimeMillis() < deadline) {
			Thread.sleep(100);
		}
		assertThat(count("SELECT COUNT(*) FROM sys_oper_log WHERE request_uri LIKE '/it/demo-chain/%'"))
			.as("全部经日志账号落库").isEqualTo(before + n);
		assertThat(count("SELECT COUNT(*) FROM (SELECT prev_hash FROM sys_oper_log WHERE chain_prev IS NOT NULL "
			+ "GROUP BY prev_hash HAVING COUNT(*) > 1) t")).as("没有重复的 prev_hash").isZero();
		// 只验本测试写入的这一段：CI 用外部服务库，库里可能留着别的运行（别的 SM2 密钥）签的旧记录
		Map<String, Object> verified = operationLogService.verify(n);
		assertThat(verified.get("valid")).as(String.valueOf(verified)).isEqualTo(true);
		assertThat(verified.get("verified")).as(String.valueOf(verified)).isEqualTo(n);
		assertThat(count("SELECT COUNT(*) FROM (SELECT request_uri FROM sys_oper_log ORDER BY id DESC LIMIT " + n
			+ ") t WHERE request_uri LIKE '/it/demo-chain/%'")).as("验签窗口里都是本用例写的").isEqualTo(n);
	}

	@Test
	void lockHeldLongerThanTimeoutDropsThatEntryAndCounts() throws Exception {
		// 先写一条锚点，最后验「锚点 → 放锁后那条」这一段链
		SysOperLog anchor = chainEntry(2);
		anchor.setRequestUri("/it/demo-lock-timeout-before");
		long anchorBefore = count("SELECT COUNT(*) FROM sys_oper_log WHERE request_uri = '/it/demo-lock-timeout-before'");
		TenantContext.execute(PLATFORM_TENANT, () -> operationLogService.saveAsync(anchor));
		long anchorDeadline = System.currentTimeMillis() + 15_000;
		while (count("SELECT COUNT(*) FROM sys_oper_log WHERE request_uri = '/it/demo-lock-timeout-before'") == anchorBefore
			&& System.currentTimeMillis() < anchorDeadline) {
			Thread.sleep(100);
		}
		assertThat(count("SELECT COUNT(*) FROM sys_oper_log WHERE request_uri = '/it/demo-lock-timeout-before'"))
			.as("锚点已落库").isEqualTo(anchorBefore + 1);
		long dropped = operationLogService.demoLockTimeoutDroppedCount();
		long before = count("SELECT COUNT(*) FROM sys_oper_log WHERE request_uri = '/it/demo-lock-timeout'");
		// owner 连接持有链头行锁超过 lock_timeout（3s）
		try (Connection c = DriverManager.getConnection(primaryJdbcUrl(), primaryJdbcUsername(), primaryJdbcPassword())) {
			c.setAutoCommit(false);
			try (Statement st = c.createStatement()) {
				st.execute("SELECT id FROM sys_oper_log_chain WHERE id = 1 FOR UPDATE");
				SysOperLog l = chainEntry(0);
				l.setRequestUri("/it/demo-lock-timeout");
				TenantContext.execute(PLATFORM_TENANT, () -> operationLogService.saveAsync(l));
				long deadline = System.currentTimeMillis() + 15_000;
				while (operationLogService.demoLockTimeoutDroppedCount() == dropped && System.currentTimeMillis() < deadline) {
					Thread.sleep(100);
				}
			}
			c.rollback();
		}
		assertThat(operationLogService.demoLockTimeoutDroppedCount()).as("超时计数加一").isEqualTo(dropped + 1);
		assertThat(count("SELECT COUNT(*) FROM sys_oper_log WHERE request_uri = '/it/demo-lock-timeout'"))
			.as("超时那条被丢弃").isEqualTo(before);
		// 锁放开后照常追加，链仍完整
		long afterBefore = count("SELECT COUNT(*) FROM sys_oper_log WHERE request_uri = '/it/demo-lock-timeout-after'");
		SysOperLog after = chainEntry(1);
		after.setRequestUri("/it/demo-lock-timeout-after");
		TenantContext.execute(PLATFORM_TENANT, () -> operationLogService.saveAsync(after));
		long deadline = System.currentTimeMillis() + 15_000;
		while (count("SELECT COUNT(*) FROM sys_oper_log WHERE request_uri = '/it/demo-lock-timeout-after'") == afterBefore
			&& System.currentTimeMillis() < deadline) {
			Thread.sleep(100);
		}
		assertThat(count("SELECT COUNT(*) FROM sys_oper_log WHERE request_uri = '/it/demo-lock-timeout-after'")).isEqualTo(afterBefore + 1);
		Map<String, Object> verified = operationLogService.verify(2);
		assertThat(verified.get("valid")).as(String.valueOf(verified)).isEqualTo(true);
		assertThat(verified.get("verified")).as(String.valueOf(verified)).isEqualTo(2);
		assertThat(count("SELECT COUNT(*) FROM (SELECT request_uri FROM sys_oper_log ORDER BY id DESC LIMIT 2) t "
			+ "WHERE request_uri IN ('/it/demo-lock-timeout-before', '/it/demo-lock-timeout-after')"))
			.as("验签窗口就是锚点和 after 两条").isEqualTo(2);
	}

	@Test
	void readOnlyAccountCannotUpdateOrLockOperLogChain() {
		for (String sql : List.of(
			"UPDATE sys_oper_log_chain SET update_time = CURRENT_TIMESTAMP WHERE id = 1",
			"SELECT id FROM sys_oper_log_chain WHERE id = 1 FOR UPDATE",
			"SELECT id FROM sys_oper_log ORDER BY id DESC LIMIT 1 FOR UPDATE",
			"UPDATE sys_oper_log SET title = title WHERE 1 = 0")) {
			assertThatThrownBy(() -> {
				try (Connection c = DriverManager.getConnection(primaryJdbcUrl(), "mugsun_demo_ro", RO_PASSWORD);
					 Statement st = c.createStatement()) {
					st.execute(sql);
				}
			}).as(sql).isInstanceOf(SQLException.class).extracting(e -> ((SQLException) e).getSQLState()).isEqualTo("42501");
		}
	}

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

		ResponseEntity<String> list = get("/system/user/page?current=1&size=5", token);
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
	void perIpRateLimitIsSharedThroughRedis() throws Exception {
		String ip = "198.51.100." + (System.nanoTime() % 200);
		for (int i = 0; i < 120; i++) {
			assertThat(rateLimiter.tryAcquire(DemoRateLimiter.Scope.TRACK, ip)).as("token %d", i).isTrue();
		}
		assertThat(rateLimiter.tryAcquire(DemoRateLimiter.Scope.TRACK, ip)).isFalse();
		// 别的 IP、别的维度不受影响
		assertThat(rateLimiter.tryAcquire(DemoRateLimiter.Scope.TRACK, ip + "1")).isTrue();
		assertThat(rateLimiter.tryAcquire(DemoRateLimiter.Scope.LOGIN, ip)).isTrue();
		// 桶状态在 Redis（demo 专用 db）里，多实例共享
		assertThat(context.getBean(org.springframework.data.redis.core.StringRedisTemplate.class)
			.hasKey("mugsun:demo:rl:track:" + ip)).isTrue();
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

	/** 原样发出请求行（不经 RestTemplate 的 URI 模板编码），看真实 Tomcat + 过滤器链的结果 */
	private java.net.http.HttpResponse<String> raw(String method, String rawPath) throws Exception {
		java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
		java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(
			java.net.URI.create(rest.getRootUri() + rawPath));
		b = "POST".equals(method)
			? b.header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
			: b.method(method, java.net.http.HttpRequest.BodyPublishers.noBody());
		return client.send(b.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void socialSourcesOnlyExactGetPassesThroughRealFilterChain() throws Exception {
		assertThat(raw("GET", "/auth/social/sources").statusCode()).isEqualTo(200);
		assertThat(raw("HEAD", "/auth/social/sources").statusCode()).isEqualTo(200);
		for (String[] c : new String[][]{
			{"GET", "/auth/social/sources/"},
			{"GET", "/auth/social/sources;jsessionid=ABC123"},
			{"GET", "/auth/social/%2573ources"},
			{"GET", "/AUTH/SOCIAL/SOURCES"},
			{"POST", "/auth/social/sources"}}) {
			java.net.http.HttpResponse<String> r = raw(c[0], c[1]);
			assertThat(r.statusCode()).as(c[0] + " " + c[1]).isEqualTo(403);
			assertThat(r.body()).as(c[0] + " " + c[1]).contains("DEMO_READONLY");
		}
	}
}
