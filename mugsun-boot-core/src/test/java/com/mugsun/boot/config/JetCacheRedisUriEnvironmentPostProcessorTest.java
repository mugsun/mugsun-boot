package com.mugsun.boot.config;

import org.apache.commons.logging.LogFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

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
	void skipsWhenHostPlaceholderUnresolved() throws Exception {
		// 模拟 prod：host=${REDIS_HOST} 且未注入 —— 不得抛，也不合成 uri
		StandardEnvironment env = prodDemo(Map.of());
		processor.postProcessEnvironment(env, null);
		assertThat(env.getPropertySources().contains(JetCacheRedisUriEnvironmentPostProcessor.SOURCE)).isFalse();
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
