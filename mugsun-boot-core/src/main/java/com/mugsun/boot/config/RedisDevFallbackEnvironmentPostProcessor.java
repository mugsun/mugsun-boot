package com.mugsun.boot.config;

import com.mugsun.boot.common.env.DeployProfiles;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

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
		String mode = env.getProperty(MODE_KEY, "auto").trim().toLowerCase();
		boolean nonProd = DeployProfiles.isNonProd(env);
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
		// addFirst 覆盖 application.yml 缺省；命令行 / 环境变量仍优先。
		map.put("mugsun.captcha.store", "memory");
		map.put("mugsun.websocket.sender-type", "local");
		map.put("mugsun.serial-number.provider", "db");
		map.put("mugsun.login-lock.store", "memory");
		map.put("mugsun.session.store", "memory");
		map.put("jetcache.remote.default.type", "mock");
		map.put(APPLIED_KEY, "true");
		env.getPropertySources().addFirst(new MapPropertySource(SOURCE, map));

		String host = env.getProperty("spring.data.redis.host", "localhost");
		String port = env.getProperty("spring.data.redis.port", "6379");
		log.warn("开发态未连上 Redis（" + host + ":" + port
			+ "），已降级：Sa-Token/验证码/登录锁=memory，WebSocket=local，单号=db，JetCache remote=mock。"
			+ "生产请提供 Redis；强制 Redis 可设 mugsun.redis.mode=redis");
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
