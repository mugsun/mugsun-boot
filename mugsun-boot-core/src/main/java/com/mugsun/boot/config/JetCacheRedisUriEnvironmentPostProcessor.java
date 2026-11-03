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
 * <p>host 占位未解析且口令非空、或口令键占位未解析时 fail-fast，禁止静默降级到无口令 localhost。
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

		Resolved host = firstResolved(env,
			"spring.data.redis.host", "MUGSUN_REDIS_HOST", "REDIS_HOST");
		Resolved password = firstResolved(env,
			"spring.data.redis.password", "REDIS_PASSWORD");

		// P0-2：口令键占位未解析 → 禁止当空口令合成无 AUTH URI
		if (password.unresolved) {
			throw new IllegalStateException(
				"Redis 口令占位符未解析（spring.data.redis.password / REDIS_PASSWORD），"
					+ "已拒绝合成 jetcache uri（禁止按空口令降级）");
		}

		String passwordValue = password.value != null ? password.value : "";
		boolean hasPassword = StringUtils.hasText(passwordValue);

		// host 未配置或占位未解析
		if (!host.hasText()) {
			// P0-1：口令已在、host 缺/未解析 → 禁止跳过
			if (hasPassword) {
				throw new IllegalStateException(
					"Redis host 未配置或占位符未解析，但口令非空，已拒绝启动"
						+ "（禁止 JetCache 静默落到无口令默认 uri）");
			}
			// LabSeed / 双端空口令：跳过，不合成、不覆盖
			log.warn("跳过 jetcache uri 合成：Redis host 未配置或占位未解析，且口令为空");
			return;
		}

		int port = parsePort(firstNonBlank(
			optional(env, "spring.data.redis.port"),
			optional(env, "MUGSUN_REDIS_PORT"),
			optional(env, "REDIS_PORT"),
			"6379"));
		int database = parseDatabase(firstNonBlank(
			optional(env, "spring.data.redis.database"),
			optional(env, "REDIS_DB"),
			"0"));

		String uri = JetCacheRedisUri.build(host.value.trim(), port, passwordValue, database);

		Map<String, Object> map = new LinkedHashMap<>();
		map.put(URI_KEY, uri);
		insertBelowSystemSources(env.getPropertySources(), new MapPropertySource(SOURCE, map));
		if (hasPassword) {
			log.debug("已按 spring.data.redis 合成 jetcache uri（口令已 URL 编码，长度="
				+ passwordValue.length() + "）");
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

	/** 单键解析：区分缺省、有值、占位未解析。 */
	static Resolved resolve(ConfigurableEnvironment env, String key) {
		try {
			String v = env.getProperty(key);
			return new Resolved(v, false);
		} catch (PlaceholderResolutionException e) {
			return Resolved.UNRESOLVED;
		}
	}

	/**
	 * 按优先级取第一个有文本的值；若途中遇占位未解析且其后也无有文本值，则整体视为 unresolved。
	 */
	static Resolved firstResolved(ConfigurableEnvironment env, String... keys) {
		boolean sawUnresolved = false;
		if (keys == null) {
			return Resolved.ABSENT;
		}
		for (String key : keys) {
			Resolved r = resolve(env, key);
			if (r.unresolved) {
				sawUnresolved = true;
				continue;
			}
			if (StringUtils.hasText(r.value)) {
				return r;
			}
		}
		if (sawUnresolved) {
			return Resolved.UNRESOLVED;
		}
		return Resolved.ABSENT;
	}

	/** 占位符无法解析时视为未配置（返回 null / 默认），不抛。 */
	static String optional(ConfigurableEnvironment env, String key) {
		return resolve(env, key).value;
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

	/** 属性解析结果：value 可为 null；unresolved 表示占位未解析。 */
	static final class Resolved {
		static final Resolved ABSENT = new Resolved(null, false);
		static final Resolved UNRESOLVED = new Resolved(null, true);

		final String value;
		final boolean unresolved;

		Resolved(String value, boolean unresolved) {
			this.value = value;
			this.unresolved = unresolved;
		}

		boolean hasText() {
			return StringUtils.hasText(value);
		}
	}
}
