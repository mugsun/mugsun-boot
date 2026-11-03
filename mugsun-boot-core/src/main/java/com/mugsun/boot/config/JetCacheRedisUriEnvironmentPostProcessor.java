package com.mugsun.boot.config;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.env.ConfigurableEnvironment;
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

	/** 单测用。 */
	public JetCacheRedisUriEnvironmentPostProcessor(Log log) {
		this.log = log;
	}

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
		String type = env.getProperty(TYPE_KEY, "redis.lettuce").trim();
		if ("mock".equalsIgnoreCase(type)) {
			return;
		}
		String host = firstNonBlank(
			env.getProperty("spring.data.redis.host"),
			env.getProperty("MUGSUN_REDIS_HOST"),
			env.getProperty("REDIS_HOST"));
		if (!StringUtils.hasText(host)) {
			return;
		}
		int port = parsePort(env.getProperty("spring.data.redis.port",
			env.getProperty("MUGSUN_REDIS_PORT",
				env.getProperty("REDIS_PORT", "6379"))));
		String password = env.getProperty("spring.data.redis.password",
			env.getProperty("REDIS_PASSWORD", ""));
		if (password == null) {
			password = "";
		}
		int database = parseDatabase(env.getProperty("spring.data.redis.database",
			env.getProperty("REDIS_DB", "0")));

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
