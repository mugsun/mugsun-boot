package com.mugsun.boot.config;

import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JetCacheRedisUriEnvironmentPostProcessorTest {

	private final JetCacheRedisUriEnvironmentPostProcessor processor =
		new JetCacheRedisUriEnvironmentPostProcessor(LogFactory.getLog(JetCacheRedisUriEnvironmentPostProcessorTest.class));

	@Test
	void prodDemoSynthesizesEncodedUriFromSpringDataRedis() throws Exception {
		StandardEnvironment env = prodDemo(Map.of(
			"REDIS_PASSWORD", "p@ss/%x",
			"REDIS_HOST", "redis",
			"REDIS_PORT", "6379",
			"REDIS_DB", "0",
			"MUGSUN_DEMO_REDIS_DB", "4"));
		processor.postProcessEnvironment(env, null);
		assertThat(env.getProperty("jetcache.remote.default.uri"))
			.isEqualTo("redis://:p%40ss%2F%25x@redis:6379/4");
		assertThat(env.getPropertySources().contains(JetCacheRedisUriEnvironmentPostProcessor.SOURCE)).isTrue();
	}

	@Test
	void specialCharsAtPercentSlash() {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		env.getPropertySources().addFirst(new MapPropertySource("redis", Map.of(
			"spring.data.redis.host", "r.local",
			"spring.data.redis.port", "56379",
			"spring.data.redis.password", "a@b%c/d",
			"spring.data.redis.database", "3",
			"jetcache.remote.default.type", "redis.lettuce")));
		processor.postProcessEnvironment(env, null);
		String uri = env.getProperty("jetcache.remote.default.uri");
		assertThat(uri).isEqualTo("redis://:a%40b%25c%2Fd@r.local:56379/3");
		JetCacheRedisUri.assertRoundTrip(uri, "r.local", 56379, "a@b%c/d", 3);
	}

	@Test
	void skipsWhenRemoteIsMock() {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		env.getPropertySources().addFirst(new MapPropertySource("cfg", Map.of(
			"spring.data.redis.host", "localhost",
			"spring.data.redis.password", "a@b",
			"jetcache.remote.default.type", "mock",
			"jetcache.remote.default.uri", "redis://keep-me:6379/3")));
		processor.postProcessEnvironment(env, null);
		assertThat(env.getProperty("jetcache.remote.default.uri")).isEqualTo("redis://keep-me:6379/3");
		assertThat(env.getPropertySources().contains(JetCacheRedisUriEnvironmentPostProcessor.SOURCE)).isFalse();
	}

	@Test
	void invalidPortFailsFast() {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		env.getPropertySources().addFirst(new MapPropertySource("cfg", Map.of(
			"spring.data.redis.host", "localhost",
			"spring.data.redis.port", "not-a-port",
			"jetcache.remote.default.type", "redis.lettuce")));
		assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("port");
	}

	@Test
	void skipsWhenHostPlaceholderUnresolvedAndPasswordEmpty() throws Exception {
		// LabSeed / 双端空：host=${REDIS_HOST} 未注入，password 默认空 —— 跳过，不合成
		StandardEnvironment env = prodDemo(Map.of());
		processor.postProcessEnvironment(env, null);
		assertThat(env.getPropertySources().contains(JetCacheRedisUriEnvironmentPostProcessor.SOURCE)).isFalse();
	}

	/** CASE_A：口令已在、host 占位未解析 → 必须 fail-fast，禁止静默跳过。 */
	@Test
	void failsFastWhenPasswordSetButHostUnresolved() throws Exception {
		StandardEnvironment env = prodDemo(Map.of("REDIS_PASSWORD", "s3cret@x"));
		assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("host")
			.hasMessageNotContaining("s3cret");
		assertThat(env.getPropertySources().contains(JetCacheRedisUriEnvironmentPostProcessor.SOURCE)).isFalse();
	}

	/** CASE_C：口令键占位未解析 → 禁止按空口令合成无 AUTH URI，且不覆盖已有 uri。 */
	@Test
	void failsFastWhenPasswordPlaceholderUnresolved() {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		Map<String, Object> cfg = new HashMap<>();
		cfg.put("spring.data.redis.host", "redis.local");
		cfg.put("spring.data.redis.port", "6379");
		cfg.put("spring.data.redis.database", "3");
		cfg.put("spring.data.redis.password", "${REDIS_PASSWORD}");
		cfg.put("jetcache.remote.default.type", "redis.lettuce");
		cfg.put("jetcache.remote.default.uri", "redis://keep-existing:6379/3");
		env.getPropertySources().addFirst(new MapPropertySource("cfg", cfg));
		assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("口令");
		assertThat(env.getPropertySources().contains(JetCacheRedisUriEnvironmentPostProcessor.SOURCE)).isFalse();
		// 未合成覆盖：仍是原字面（getProperty 会解析 host 侧，uri 字面无占位）
		assertThat(env.getProperty("jetcache.remote.default.uri"))
			.isEqualTo("redis://keep-existing:6379/3");
	}


	@Test
	void blankWhitespacePasswordNormalizedAsNoAuth() {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		Map<String, Object> cfg = new HashMap<>();
		cfg.put("spring.data.redis.host", "r.local");
		cfg.put("spring.data.redis.port", "6379");
		cfg.put("spring.data.redis.password", "   ");
		cfg.put("spring.data.redis.database", "3");
		cfg.put("jetcache.remote.default.type", "redis.lettuce");
		env.getPropertySources().addFirst(new MapPropertySource("cfg", cfg));
		processor.postProcessEnvironment(env, null);
		assertThat(env.getProperty("jetcache.remote.default.uri"))
			.isEqualTo("redis://r.local:6379/3");
	}

	/** P2：更高优先级显式 uri 不被合成覆盖，但须与 spring.data.redis 回解析一致。 */
	@Test
	void explicitUriBypassStillRoundTripChecked() {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		// systemProperties 模拟更高优先级显式 uri（已编码）
		env.getPropertySources().addFirst(new MapPropertySource(
			StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
			Map.of("jetcache.remote.default.uri", "redis://:p%40ss@r.local:6379/3")));
		env.getPropertySources().addLast(new MapPropertySource("cfg", Map.of(
			"spring.data.redis.host", "r.local",
			"spring.data.redis.port", "6379",
			"spring.data.redis.password", "p@ss",
			"spring.data.redis.database", "3",
			"jetcache.remote.default.type", "redis.lettuce")));
		processor.postProcessEnvironment(env, null);
		assertThat(env.getProperty("jetcache.remote.default.uri"))
			.isEqualTo("redis://:p%40ss@r.local:6379/3");
	}

	@Test
	void explicitUriBypassMismatchedPasswordFailsFast() {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		// 显式 uri 口令未编码 / 与 spring.data.redis 不一致 → 拒绝
		env.getPropertySources().addFirst(new MapPropertySource(
			StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
			Map.of("jetcache.remote.default.uri", "redis://:p@ss@r.local:6379/3")));
		env.getPropertySources().addLast(new MapPropertySource("cfg", Map.of(
			"spring.data.redis.host", "r.local",
			"spring.data.redis.port", "6379",
			"spring.data.redis.password", "p@ss",
			"spring.data.redis.database", "3",
			"jetcache.remote.default.type", "redis.lettuce")));
		assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("拒绝启动")
			.hasMessageNotContaining("p@ss");
	}

	private static StandardEnvironment prodDemo(Map<String, Object> deploy) throws Exception {
		StandardEnvironment env = new StandardEnvironment();
		var sources = env.getPropertySources();
		sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		sources.addFirst(new MapPropertySource("deploy", deploy));
		YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
		for (String file : List.of("application.yml", "application-prod.yml", "application-demo.yml")) {
			for (var ps : loader.load(file, new ClassPathResource(file))) {
				sources.addAfter("deploy", ps);
			}
		}
		return env;
	}
}
