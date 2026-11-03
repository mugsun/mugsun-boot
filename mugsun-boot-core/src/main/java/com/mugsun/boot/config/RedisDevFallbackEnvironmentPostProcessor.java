package com.mugsun.boot.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 开发态缺 Redis 时的约定降级：显式 active 为非生产（见 {@link DeployProfiles}）且探测不到 Redis 时，
 * 把会话、验证码、WebSocket 扇出、单号、登录锁与 JetCache remote 切到进程内实现，并打 WARN。
 * <p>生产不降级（保持原行为，缺 Redis 仍会在启动或首用时失败）。
 * {@code mugsun.redis.mode=redis} 强制不降级；{@code fallback} 在非生产强制降级（不探测）。
 * <p>生产判定与 {@link com.mugsun.boot.auth.lock.LoginLockStoreConfig}/{@link SaTokenMemorySessionConfig} 共用
 * {@link DeployProfiles#isProduction(org.springframework.core.env.Environment)}，
 * 避免 {@code common.env.DeployProfiles} 只否决字面 {@code prod} 时出现降级写入与守卫拒绝矛盾。
 */
public class RedisDevFallbackEnvironmentPostProcessor implements EnvironmentPostProcessor {

	static final String SOURCE = "mugsunRedisDevFallback";
	static final String MODE_KEY = "mugsun.redis.mode";
	static final String APPLIED_KEY = "mugsun.redis.dev-fallback-applied";
	private static final int PROBE_TIMEOUT_MS = 200;

	private final Log log;

	public RedisDevFallbackEnvironmentPostProcessor(DeferredLogFactory logFactory) {
		this.log = logFactory.getLog(RedisDevFallbackEnvironmentPostProcessor.class);
	}

	/** 单测用：直接注入 Log，避开 DeferredLogFactory 接口演进。 */
	RedisDevFallbackEnvironmentPostProcessor(Log log) {
		this.log = log;
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
		// mode 在写入 PropertySource 之前读取，故 mugsun.redis.mode=redis 始终有效。
		String mode = env.getProperty(MODE_KEY, "auto").trim().toLowerCase();
		boolean nonProd = !DeployProfiles.isProduction(env);
		boolean forceFallback = "fallback".equals(mode) || "local".equals(mode) || "memory".equals(mode);
		boolean forceRedis = "redis".equals(mode) || "required".equals(mode);

		if (forceRedis) {
			return;
		}
		if (!nonProd && !forceFallback) {
			return;
		}
		if (forceFallback && !nonProd) {
			log.warn("mugsun.redis.mode=" + mode + " 仅非生产有效，生产已忽略");
			return;
		}
		if (!forceFallback && redisReachable(env)) {
			return;
		}

		Map<String, Object> map = new LinkedHashMap<>();
		map.put("mugsun.captcha.store", "memory");
		map.put("mugsun.websocket.sender-type", "local");
		map.put("mugsun.serial-number.provider", "db");
		map.put("mugsun.login-lock.store", "memory");
		map.put("mugsun.session.store", "memory");
		map.put("jetcache.remote.default.type", "mock");
		map.put(APPLIED_KEY, "true");
		// 插在 systemEnvironment 之后：覆盖 application.yml 缺省，但不压过命令行 / 系统属性 / 环境变量。
		insertBelowSystemSources(env.getPropertySources(), new MapPropertySource(SOURCE, map));

		String host = env.getProperty("spring.data.redis.host", "localhost");
		String port = env.getProperty("spring.data.redis.port", "6379");
		log.warn("开发态未连上 Redis（" + host + ":" + port
			+ "），已降级：Sa-Token/验证码/登录锁=memory，WebSocket=local，单号=db，JetCache remote=mock。"
			+ "生产请提供 Redis；强制 Redis 可设 mugsun.redis.mode=redis");
	}

	/**
	 * 优先插在 {@code systemEnvironment}（或 {@code systemProperties}）之后；
	 * 单测剥离系统源时改 {@code addLast}，保证更高源（命令行模拟、测试 Map）仍优先。
	 */
	static void insertBelowSystemSources(MutablePropertySources sources, MapPropertySource ps) {
		if (sources.contains(SOURCE)) {
			sources.replace(SOURCE, ps);
			return;
		}
		if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
			sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, ps);
		} else if (sources.contains(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)) {
			sources.addAfter(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, ps);
		} else {
			sources.addLast(ps);
		}
	}

	static boolean redisReachable(ConfigurableEnvironment env) {
		String host = env.getProperty("spring.data.redis.host",
			env.getProperty("MUGSUN_REDIS_HOST", "localhost"));
		int port;
		try {
			port = Integer.parseInt(env.getProperty("spring.data.redis.port",
				env.getProperty("MUGSUN_REDIS_PORT", "6379")));
		} catch (NumberFormatException e) {
			return false;
		}
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
			return true;
		} catch (IOException e) {
			return false;
		}
	}
}
