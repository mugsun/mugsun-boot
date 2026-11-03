package com.mugsun.boot.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.PlaceholderResolutionException;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用 {@code spring.data.redis} 分字段合成 JetCache remote uri（口令 URL 编码）。
 * <p>插在 systemEnvironment 之后：覆盖 application-*.yml 里可能存在的裸拼口令 uri，
 * 但不压过命令行 / 系统属性 / 环境变量里显式的 {@code jetcache.remote.default.uri}。
 * remote 已是 mock（开发态降级）时跳过。
 */
public class JetCacheRedisUriEnvironmentPostProcessor implements EnvironmentPostProcessor {

	static final String SOURCE = "mugsunJetCacheRedisUri";
	static final String URI_KEY = "jetcache.remote.default.uri";
	static final String TYPE_KEY = "jetcache.remote.default.type";

	private final Log log;

	public JetCacheRedisUriEnvironmentPostProcessor(DeferredLogFactory logFactory) {
		this.log = logFactory.getLog(JetCacheRedisUriEnvironmentPostProcessor.class);
	}

	/** 单测用：包可见，勿 public——SpringFactoriesLoader.FactoryInstantiator
	 * 要求恰好一个 public 构造器（DeferredLogFactory）。 */
	JetCacheRedisUriEnvironmentPostProcessor(Log log) {
		this.log = log;
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
		String type = optional(env, TYPE_KEY, "redis.lettuce").trim();
		if ("mock".equalsIgnoreCase(type)) {
			return;
		}
		// prod 的 host=${REDIS_HOST} 未注入时 getProperty 会抛 PlaceholderResolutionException；
		// 此时跳过合成，让后续组件按原语义失败，勿抢在 LabSeed 等守卫之前炸。
		String host = firstNonBlank(
			optional(env, "spring.data.redis.host"),
			optional(env, "MUGSUN_REDIS_HOST"),
			optional(env, "REDIS_HOST"));
		if (!StringUtils.hasText(host)) {
			return;
		}
		int port = parsePort(firstNonBlank(
			optional(env, "spring.data.redis.port"),
			optional(env, "MUGSUN_REDIS_PORT"),
			optional(env, "REDIS_PORT"),
			"6379"));
		String password = firstNonBlank(
			optional(env, "spring.data.redis.password"),
			optional(env, "REDIS_PASSWORD"));
		if (password == null) {
			password = "";
		}
		int database = parseDatabase(firstNonBlank(
			optional(env, "spring.data.redis.database"),
			optional(env, "REDIS_DB"),
			"0"));

		String uri;
		try {
			uri = JetCacheRedisUri.build(host.trim(), port, password, database);
		} catch (RuntimeException e) {
			// fail fast：不要吞掉，让启动失败
			throw e;
		}

		Map<String, Object> map = new LinkedHashMap<>();
		map.put(URI_KEY, uri);
		insertBelowSystemSources(env.getPropertySources(), new MapPropertySource(SOURCE, map));
		if (StringUtils.hasText(password)) {
			log.debug("已按 spring.data.redis 合成 jetcache uri（口令已 URL 编码，长度="
				+ password.length() + "）");
		}
	}

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
			sources.addFirst(ps);
		}
	}


	/** 占位符无法解析时视为未配置（返回 null / 默认），不抛。 */
	static String optional(ConfigurableEnvironment env, String key) {
		try {
			return env.getProperty(key);
		} catch (PlaceholderResolutionException e) {
			return null;
		}
	}

	static String optional(ConfigurableEnvironment env, String key, String defaultValue) {
		String v = optional(env, key);
		return v != null ? v : defaultValue;
	}

	private static String firstNonBlank(String... values) {

		if (values == null) {
			return null;
		}
		for (String v : values) {
			if (StringUtils.hasText(v)) {
				return v;
			}
		}
		return null;
	}

	private static int parsePort(String raw) {
		try {
			int p = Integer.parseInt(raw.trim());
			if (p < 1 || p > 65535) {
				throw new IllegalStateException("Redis port 非法: " + raw);
			}
			return p;
		} catch (NumberFormatException e) {
			throw new IllegalStateException("Redis port 非法: " + raw, e);
		}
	}

	private static int parseDatabase(String raw) {
		try {
			int db = Integer.parseInt(raw.trim());
			if (db < 0) {
				throw new IllegalStateException("Redis database 非法: " + raw);
			}
			return db;
		} catch (NumberFormatException e) {
			throw new IllegalStateException("Redis database 非法: " + raw, e);
		}
	}
}
