package com.mugsun.boot;

import cn.hutool.crypto.SmUtil;
import cn.hutool.crypto.asymmetric.KeyType;
import cn.hutool.crypto.asymmetric.SM2;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.crypto.engines.SM2Engine;
import com.mugsun.boot.common.crypto.Sm4TestKeys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集成测试基座：Testcontainers 单例容器（PostgreSQL 16 / Redis 7，JVM 级复用）+ 动态配置覆写。
 * <p>数据全部落在容器随机端口，绝不触达本机 5432/6379 的开发环境。
 * <p>复刻前端真实登录链路（验证码总开关关闭）：取 SM2 公钥 → sm-crypto 同构加密（C1C3C2、无 04 前缀）→ 登录换 token。
 */
// 启动守卫只对 dev/test/local 放行（DeployProfiles），集成测试显式激活 test
// multipart 计数包装放在基类：只在子类 @Import 会改上下文缓存 key、多起一个上下文，撞 warm-flow 静态 SpringUtil。
// 放在这里所有子类 key 一致；包装只委托不改行为（见 MultipartResolverRecorder）
@Import(com.mugsun.boot.security.MultipartResolverRecorder.class)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		// 双因子码/短信码回显仅测试环境开启（生产配置恒 false）
		"mugsun.captcha.show-code=true",
		// 存量用例走验证码总开关关闭（mugsun.captcha.enabled=false）的登录链路；
		// 行为验证码链路见 captcha 包下的专项测试（运行期 props.setEnabled(true)，用完复原）
		"mugsun.captcha.enabled=false",
		"mugsun.sms.show-code=true",
		"mugsun.crypto.sm4-key=" + Sm4TestKeys.INTEGRATION_KEY,
		// 接口加密密钥不写死：测试用进程内临时密钥（strict-keys 由 application.yml 默认 false）
		"mugsun.crypto.allow-ephemeral-keys=true",
		"mugsun.web.front-url=http://frontend.test:3006",
		// 测试上下文没有显式 profile，按生产规则注册默认关闭；存量注册用例需要开放。
		// 关闭场景见 RegisterOffApiTest；IP 限流放宽，避免同 JVM 多个注册用例互相挤占，账号维度保持默认
		"mugsun.auth.register.enabled=true",
		"mugsun.auth.register.rate-limit.per-ip=1000"
	})
public abstract class AbstractIntegrationTest {

	/** 平台超管租户编号 */
	protected static final String PLATFORM_TENANT = "000000";
	/** 平台超管账号（DataInitializer 首次启动播种） */
	protected static final String ADMIN_USERNAME = "admin";
	protected static final String ADMIN_PASSWORD = "123456";
	/** 默认登录客户端（V40 播种：验证码开） */
	protected static final String CLIENT_WEB = "web";
	/** 租户请求头（伪造越权判定依据） */
	protected static final String TENANT_HEADER = "X-Tenant-Id";
	/** Sa-Token 令牌请求头（裸 token，无前缀） */
	protected static final String TOKEN_HEADER = "Authorization";

	/** CI 可通过环境变量切换到外部服务（GitHub Actions services: Postgres/Redis） */
	private static boolean useExternalServices() {
		String flag = System.getenv("MUGSUN_TEST_USE_SERVICES");
		return flag != null && ("true".equalsIgnoreCase(flag) || "1".equals(flag));
	}
	private static String envOr(String key, String def) {
		String v = System.getenv(key);
		return v == null || v.isBlank() ? def : v;
	}
	private static final String EXT_PG_HOST = envOr("POSTGRES_HOST", "127.0.0.1");
	private static final int EXT_PG_PORT = Integer.parseInt(envOr("POSTGRES_PORT", "5432"));
	private static final String EXT_PG_DB   = envOr("POSTGRES_DB", "mugsun");
	private static final String EXT_PG_USER = envOr("POSTGRES_USER", "mugsun");
	private static final String EXT_PG_PASS = envOr("POSTGRES_PASSWORD", "mugsun");
	private static final String EXT_REDIS_HOST = envOr("REDIS_HOST", "127.0.0.1");
	private static final int EXT_REDIS_PORT = Integer.parseInt(envOr("REDIS_PORT", "6379"));

	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
		DockerImageName.parse("postgres:16-alpine"))
		.withDatabaseName("mugsun")
		.withUsername("mugsun")
		.withPassword("mugsun");

	private static final GenericContainer<?> REDIS = new GenericContainer<>(
		DockerImageName.parse("redis:7-alpine"))
		.withExposedPorts(6379);

	/** 埋点库名（与模块 yml 的 track.url 库名段一致；建库/迁移由 TrackFlywayConfig 经 primary 完成） */
	protected static final String TRACK_DB = "mugsun_track";

	/** classpath 是否带有埋点模块（core-only 测试勿注册 track 数据源，避免连不存在的库） */
	private static final boolean TRACK_MODULE_PRESENT = isPresent("com.mugsun.boot.track.TrackFlywayConfig");

	private static boolean isPresent(String className) {
		try {
			Class.forName(className);
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}

	static {
		if (!useExternalServices()) {
			POSTGRES.start();
			REDIS.start();
		}
	}

	/** 同容器双库：把容器 JDBC URL 的库名段替换为埋点库名 */
	private static String trackJdbcUrl() {
		String url = useExternalServices()
			? "jdbc:postgresql://" + EXT_PG_HOST + ":" + EXT_PG_PORT + "/" + EXT_PG_DB
			: POSTGRES.getJdbcUrl();
		return url.substring(0, url.lastIndexOf('/')) + "/" + TRACK_DB;
	}

	/**
	 * 子类在静态初始化块里放入的覆盖项，在 {@link #containerProperties} 末尾注册、优先于上面的默认值
	 * （父子各写一个 {@code @DynamicPropertySource} 时父类后执行，子类无法覆盖同名属性）。
	 * 只给独立 surefire fork 里的测试用（如 DemoAccountsApiTest），否则会污染同 JVM 后续上下文。
	 */
	protected static final java.util.Map<String, java.util.function.Supplier<Object>> PROPERTY_OVERRIDES =
		new java.util.LinkedHashMap<>();

	@DynamicPropertySource
	static void containerProperties(DynamicPropertyRegistry registry) {
		if (useExternalServices()) {
			registry.add("mybatis-flex.datasource.primary.url",
				() -> "jdbc:postgresql://" + EXT_PG_HOST + ":" + EXT_PG_PORT + "/" + EXT_PG_DB);
			registry.add("mybatis-flex.datasource.primary.username", () -> EXT_PG_USER);
			registry.add("mybatis-flex.datasource.primary.password", () -> EXT_PG_PASS);
		} else {
			registry.add("mybatis-flex.datasource.primary.url", POSTGRES::getJdbcUrl);
			registry.add("mybatis-flex.datasource.primary.username", POSTGRES::getUsername);
			registry.add("mybatis-flex.datasource.primary.password", POSTGRES::getPassword);
		}
		// 埋点独立数据源：仅全量装配（track jar 在 classpath）时注册，保持上下文缓存键稳定
		if (TRACK_MODULE_PRESENT) {
			registry.add("mybatis-flex.datasource.track.url", () -> trackJdbcUrl());
			registry.add("mybatis-flex.datasource.track.username",
				() -> useExternalServices() ? EXT_PG_USER : POSTGRES.getUsername());
			registry.add("mybatis-flex.datasource.track.password",
				() -> useExternalServices() ? EXT_PG_PASS : POSTGRES.getPassword());
		}
		if (useExternalServices()) {
			registry.add("spring.data.redis.host", () -> EXT_REDIS_HOST);
			registry.add("spring.data.redis.port", () -> EXT_REDIS_PORT);
		} else {
			registry.add("spring.data.redis.host", REDIS::getHost);
			registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
		}
		registry.add("jetcache.remote.default.uri",
			() -> "redis://" + (useExternalServices() ? EXT_REDIS_HOST : REDIS.getHost()) + ":"
				+ (useExternalServices() ? EXT_REDIS_PORT : REDIS.getMappedPort(6379)) + "/3");
		// 测试环境不连 PowerJob Server
		registry.add("powerjob.worker.enabled", () -> "false");
		// 时间线索引后台补建：测试里按需显式打开（防 02–06 点跑 CI 时后台任务与用例抢 advisory 锁）
		registry.add("mugsun.track.timeline-index.backfill-enabled", () -> "false");
		// 文件存储落 target 下临时目录，不污染 /tmp。
		// 注意 Spring 列表绑定规则：列表在多属性源时按最高优先级源「整体替换」——只覆盖 storage-path
		// 会把该元素的 platform/enable-storage 等字段冲成默认值（平台不注册、默认平台不可用），
		// 故整元素补齐（取值与 application.yml 一致，仅存储目录改道 target/it-files/）
		registry.add("dromara.x-file-storage.local-plus[0].platform", () -> "local-plus-1");
		registry.add("dromara.x-file-storage.local-plus[0].enable-storage", () -> "true");
		registry.add("dromara.x-file-storage.local-plus[0].enable-access", () -> "true");
		registry.add("dromara.x-file-storage.local-plus[0].domain", () -> "http://127.0.0.1:8080/file/");
		registry.add("dromara.x-file-storage.local-plus[0].base-path", () -> "mugsun/");
		registry.add("dromara.x-file-storage.local-plus[0].path-patterns", () -> "/file/**");
		// 绝对路径消歧：LocalPlus 对相对 storage-path 的解析基准与测试 JVM 工作目录不一致（OssStorageApiTest 同款教训）
		registry.add("dromara.x-file-storage.local-plus[0].storage-path",
			() -> new java.io.File("target/it-files/").getAbsolutePath() + "/");
		// Redis 容器就绪，推送扇出保持 redis 模式
		registry.add("mugsun.websocket.sender-type", () -> "redis");
		// application.yml 的 SMTP 为占位符，邮件健康检查必然 DOWN 拖垮聚合 503；测试环境关闭该指标
		registry.add("management.health.mail.enabled", () -> "false");
		PROPERTY_OVERRIDES.forEach(registry::add);
	}

	@LocalServerPort
	protected int port;

	@Autowired
	protected TestRestTemplate rest;

	protected final ObjectMapper om = new ObjectMapper();

	/**
	 * 每个用例前把 Sm4Util 恢复成测试密钥：单测改过静态密钥后，缓存复用的上下文不会再初始化，
	 * 否则会用错密钥往共享库写 sys_user.id_card 等密文，后续模块启动自检解不开就拒绝启动。
	 */
	@BeforeAll
	static void resetSm4KeyBeforeClass() {
		// 子类的 @BeforeAll（如 FieldMaskApiTest 建数据）在这之后执行，也拿到正确密钥
		Sm4TestKeys.useIntegrationKey();
	}

	@BeforeEach
	void resetSm4KeyToIntegrationKey() {
		Sm4TestKeys.useIntegrationKey();
	}

	@BeforeAll
	static void containersRunning() {
		if (!useExternalServices()) {
			assertThat(POSTGRES.isRunning()).as("PostgreSQL 容器已启动").isTrue();
			assertThat(REDIS.isRunning()).as("Redis 容器已启动").isTrue();
		}
	}

	/** 解析 R 信封响应体 */
	protected JsonNode readBody(ResponseEntity<String> response) {
		try {
			return om.readTree(response.getBody());
		} catch (Exception e) {
			throw new IllegalStateException("响应体非 JSON：" + response.getBody(), e);
		}
	}

	/** 带 token 的请求头（裸 token，与前端一致） */
	protected HttpHeaders authHeaders(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		if (token != null) {
			headers.set(TOKEN_HEADER, token);
		}
		return headers;
	}

	protected ResponseEntity<String> get(String url, String token) {
		return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class);
	}

	protected ResponseEntity<String> post(String url, Object body, String token) {
		return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, authHeaders(token)), String.class);
	}

	/** 原文 POST（text/plain）：带标签的 XML 原文必须绕开 JSON 的 XSS 净化才能到达服务端 */
	protected ResponseEntity<String> postText(String url, String body, String token) {
		HttpHeaders headers = authHeaders(token);
		headers.setContentType(new MediaType(MediaType.TEXT_PLAIN, java.nio.charset.StandardCharsets.UTF_8));
		return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
	}

	/** 带伪造租户头的 GET（租户守卫测试用） */
	protected ResponseEntity<String> getWithTenantHeader(String url, String token, String tenantHeader) {
		HttpHeaders headers = authHeaders(token);
		headers.set(TENANT_HEADER, tenantHeader);
		return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
	}

	/**
	 * 完整登录链路（与前端一致，验证码总开关关闭）：SM2 公钥 → 加密密码 → 登录。
	 * 断言各步 R.code=200，返回 token。
	 */
	protected String login(String tenantId, String username, String rawPassword) {
		// 1) SM2 传输公钥（国密开关开启时前端公钥加密密码）
		ResponseEntity<String> keyResp = get("/auth/sm2-public-key", null);
		JsonNode keyData = readBody(keyResp).path("data");
		String password = rawPassword;
		if (keyData.path("gmEnabled").asBoolean()) {
			password = sm2Encrypt(rawPassword, keyData.path("publicKey").asText());
		}

		// 2) 登录
		Map<String, Object> body = new HashMap<>();
		body.put("tenantId", tenantId);
		body.put("username", username);
		body.put("password", password);
		body.put("clientId", CLIENT_WEB);
		ResponseEntity<String> loginResp = post("/auth/login", body, null);
		assertThat(loginResp.getStatusCode().value()).as("登录接口 HTTP 状态").isEqualTo(200);
		JsonNode login = readBody(loginResp);
		assertThat(login.path("code").asInt()).as("登录应成功：" + login.path("msg").asText()).isEqualTo(200);
		String token = login.path("data").path("token").asText();
		assertThat(token).isNotBlank();
		return token;
	}

	/** SM2 公钥加密（对齐前端 sm-crypto：C1C3C2，密文不带 04 前缀） */
	protected String sm2Encrypt(String plain, String publicKeyHex) {
		SM2 sm2 = SmUtil.sm2(null, publicKeyHex);
		sm2.setMode(SM2Engine.Mode.C1C3C2);
		String hex = sm2.encryptHex(plain, KeyType.PublicKey);
		return hex.startsWith("04") ? hex.substring(2) : hex;
	}

	/** 以平台超管身份登录 */
	protected String loginAdmin() {
		return login(PLATFORM_TENANT, ADMIN_USERNAME, ADMIN_PASSWORD);
	}

	/** Testcontainers 主库 JDBC（租户独立源等联调用） */
	protected static String primaryJdbcUrl() {
		return useExternalServices()
			? "jdbc:postgresql://" + EXT_PG_HOST + ":" + EXT_PG_PORT + "/" + EXT_PG_DB
			: POSTGRES.getJdbcUrl();
	}

	protected static String primaryJdbcUsername() {
		return useExternalServices() ? EXT_PG_USER : POSTGRES.getUsername();
	}

	protected static String primaryJdbcPassword() {
		return useExternalServices() ? EXT_PG_PASS : POSTGRES.getPassword();
	}

	/**
	 * 在同容器创建附加库（idempotent）。用于租户独立数据源联调，不污染主库 schema。
	 */
	protected static void ensureExtraDatabase(String databaseName) {
		String adminUrl = (useExternalServices()
			? ("jdbc:postgresql://" + EXT_PG_HOST + ":" + EXT_PG_PORT + "/postgres")
			: POSTGRES.getJdbcUrl().replaceFirst("/[^/]+$", "/postgres"));
		try (java.sql.Connection c = java.sql.DriverManager.getConnection(
			adminUrl, primaryJdbcUsername(), primaryJdbcPassword());
			 java.sql.Statement st = c.createStatement();
			 java.sql.ResultSet rs = st.executeQuery(
				"SELECT 1 FROM pg_database WHERE datname = '" + databaseName.replace("'", "") + "'")) {
			if (!rs.next()) {
				st.execute("CREATE DATABASE " + databaseName);
			}
		} catch (Exception e) {
			throw new IllegalStateException("创建附加库失败：" + databaseName, e);
		}
	}

	/** 将主库 JDBC URL 的库名段替换为指定库 */
	protected static String jdbcUrlForDatabase(String databaseName) {
		String base = primaryJdbcUrl();
		return base.replaceFirst("/[^/]+$", "/" + databaseName);
	}
}
