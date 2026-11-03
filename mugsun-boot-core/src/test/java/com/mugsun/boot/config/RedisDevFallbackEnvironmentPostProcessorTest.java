package com.mugsun.boot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class RedisDevFallbackEnvironmentPostProcessorTest {

	private final RedisDevFallbackEnvironmentPostProcessor processor =
		new RedisDevFallbackEnvironmentPostProcessor(LogFactory.getLog(RedisDevFallbackEnvironmentPostProcessor.class));

	@Test
	void nonProdUnreachableRedisAppliesFallback() {
		StandardEnvironment env = env("dev", Map.of(
			"spring.data.redis.host", "127.0.0.1",
			"spring.data.redis.port", "1"));
		processor.postProcessEnvironment(env, null);
		assertThat(env.getProperty("mugsun.redis.dev-fallback-applied")).isEqualTo("true");
		assertThat(env.getProperty("mugsun.session.store")).isEqualTo("memory");
		assertThat(env.getProperty("mugsun.captcha.store")).isEqualTo("memory");
		assertThat(env.getProperty("mugsun.websocket.sender-type")).isEqualTo("local");
		assertThat(env.getProperty("mugsun.serial-number.provider")).isEqualTo("db");
		assertThat(env.getProperty("mugsun.login-lock.store")).isEqualTo("memory");
		assertThat(env.getProperty("jetcache.remote.default.type")).isEqualTo("mock");
	}

	@Test
	void prodDoesNotFallbackEvenIfUnreachable() {
		StandardEnvironment env = env("prod", Map.of(
			"spring.data.redis.host", "127.0.0.1",
			"spring.data.redis.port", "1"));
		processor.postProcessEnvironment(env, null);
		assertThat(env.getProperty("mugsun.redis.dev-fallback-applied")).isNull();
		assertThat(env.getPropertySources().contains(RedisDevFallbackEnvironmentPostProcessor.SOURCE)).isFalse();
	}

	@Test
	void emptyActiveTreatedAsProdNoFallback() {
		StandardEnvironment env = env(new String[0], Map.of(
			"spring.data.redis.host", "127.0.0.1",
			"spring.data.redis.port", "1"));
		processor.postProcessEnvironment(env, null);
		assertThat(env.getProperty("mugsun.redis.dev-fallback-applied")).isNull();
	}

	@Test
	void stagingWithDevVetoedAsProdNoFallback() {
		StandardEnvironment env = env(new String[] {"dev", "staging"}, Map.of(
			"spring.data.redis.host", "127.0.0.1",
			"spring.data.redis.port", "1"));
		processor.postProcessEnvironment(env, null);
		assertThat(env.getProperty("mugsun.redis.dev-fallback-applied")).isNull();
	}

	@Test
	void productionAliasWithDevVetoedAsProdNoFallback() {
		StandardEnvironment env = env(new String[] {"dev", "production"}, Map.of(
			"spring.data.redis.host", "127.0.0.1",
			"spring.data.redis.port", "1"));
		processor.postProcessEnvironment(env, null);
		assertThat(env.getProperty("mugsun.redis.dev-fallback-applied")).isNull();
	}

	@Test
	void modeRedisSkipsFallbackInDev() {
		StandardEnvironment env = env("dev", Map.of(
			"mugsun.redis.mode", "redis",
			"spring.data.redis.host", "127.0.0.1",
			"spring.data.redis.port", "1"));
		processor.postProcessEnvironment(env, null);
		assertThat(env.getProperty("mugsun.redis.dev-fallback-applied")).isNull();
	}

	@Test
	void modeFallbackForcesWithoutProbeInDev() {
		StandardEnvironment env = env("dev", Map.of(
			"mugsun.redis.mode", "fallback"));
		processor.postProcessEnvironment(env, null);
		assertThat(env.getProperty("mugsun.redis.dev-fallback-applied")).isEqualTo("true");
		assertThat(env.getProperty("mugsun.session.store")).isEqualTo("memory");
	}

	@Test
	void modeFallbackIgnoredInProd() {
		StandardEnvironment env = env("prod", Map.of(
			"mugsun.redis.mode", "fallback",
			"spring.data.redis.host", "127.0.0.1",
			"spring.data.redis.port", "1"));
		processor.postProcessEnvironment(env, null);
		assertThat(env.getProperty("mugsun.redis.dev-fallback-applied")).isNull();
		assertThat(env.getPropertySources().contains(RedisDevFallbackEnvironmentPostProcessor.SOURCE)).isFalse();
	}

	@Test
	void higherPrioritySourceBeatsFallbackDefaults() {
		StandardEnvironment env = env("dev", Map.of(
			"mugsun.redis.mode", "fallback",
			"mugsun.session.store", "redis"));
		processor.postProcessEnvironment(env, null);
		assertThat(env.getProperty("mugsun.redis.dev-fallback-applied")).isEqualTo("true");
		// 测试 Map 在降级源之上（addLast 插入），命令行/更高源仍优先
		assertThat(env.getProperty("mugsun.session.store")).isEqualTo("redis");
		assertThat(env.getProperty("mugsun.captcha.store")).isEqualTo("memory");
	}

	private static StandardEnvironment env(String profile, Map<String, String> values) {
		return env(new String[] {profile}, values);
	}

	private static StandardEnvironment env(String[] profiles, Map<String, String> values) {
		StandardEnvironment e = new StandardEnvironment();
		e.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		e.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		if (profiles.length > 0) {
			e.setActiveProfiles(profiles);
		}
		Map<String, Object> map = new HashMap<>(values);
		e.getPropertySources().addFirst(new MapPropertySource("test", map));
		return e;
	}
}
