package com.mugsun.boot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.type.StandardAnnotationMetadata;

import com.alicp.jetcache.autoconfigure.RedisLettuceAutoConfiguration;

/**
 * 回归：prod / demo 曾留空 {@code jetcache.remote.default:} 注释块，SnakeYAML 扁平化为
 * 无子键的 {@code jetcache.remote.default=}。JetCacheCondition 对 remote.* 做
 * {@code key.substring(0, key.indexOf('.'))} 时遇无点号键 → StringIndexOutOfBounds Range [0,-1)。
 * 正式修：删掉空块，让 application.yml 的 type=redis.lettuce 完整继承。
 */
class JetCacheRemoteDefaultTypeInheritanceTest {

	@Test
	void prodYamlDoesNotEmitBareRemoteDefaultKey() throws Exception {
		assertNoBareRemoteDefault("application-prod.yml");
	}

	@Test
	void demoYamlDoesNotEmitBareRemoteDefaultKey() throws Exception {
		assertNoBareRemoteDefault("application-demo.yml");
	}

	@Test
	void prodProfileKeepsRedisLettuceTypeAndLettuceConditionMatches() throws Exception {
		StandardEnvironment env = load("application.yml", "application-prod.yml");
		assertThat(env.getProperty("jetcache.remote.default.type")).isEqualTo("redis.lettuce");
		assertThat(env.containsProperty("jetcache.remote.default")).isFalse();
		assertLettuceConditionMatches(env);
	}

	@Test
	void demoProfileKeepsRedisLettuceTypeAndLettuceConditionMatches() throws Exception {
		// demo 叠在默认之上（与 DemoAccountsApiTest 的 test,demo 同：demo 须继承 type）
		StandardEnvironment env = load("application.yml", "application-demo.yml");
		assertThat(env.getProperty("jetcache.remote.default.type")).isEqualTo("redis.lettuce");
		assertThat(env.containsProperty("jetcache.remote.default")).isFalse();
		assertLettuceConditionMatches(env);
	}

	@Test
	void bareRemoteDefaultKeyReproducesJetCacheConditionCrash() {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		// 模拟修前空 map：裸键 + 仍可见的 type（扁平合并下 type 可在，Condition 仍炸）
		env.getPropertySources().addFirst(new MapPropertySource("empty-map", Map.of(
			"jetcache.remote.default", "",
			"jetcache.remote.default.type", "redis.lettuce")));
		assertThatThrownBy(() -> assertLettuceConditionMatches(env))
			.isInstanceOf(StringIndexOutOfBoundsException.class);
	}

	private static void assertNoBareRemoteDefault(String classpathYaml) throws Exception {
		List<String> bare = new ArrayList<>();
		for (PropertySource<?> ps : new YamlPropertySourceLoader()
			.load(classpathYaml, new ClassPathResource(classpathYaml))) {
			if (ps instanceof EnumerablePropertySource<?> eps) {
				for (String n : eps.getPropertyNames()) {
					if ("jetcache.remote.default".equals(n)) {
						bare.add(n + "=" + eps.getProperty(n));
					}
				}
			}
		}
		assertThat(bare).as(classpathYaml + " must not emit bare jetcache.remote.default").isEmpty();
	}

	/** profile 文件后插到 deploy 之后 → 优先级高于 application.yml（同 DemoJetcacheUriTest）。 */
	private static StandardEnvironment load(String... classpathYamls) throws Exception {
		StandardEnvironment env = new StandardEnvironment();
		var sources = env.getPropertySources();
		sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		sources.addFirst(new MapPropertySource("deploy", Map.of()));
		YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
		for (String file : classpathYamls) {
			for (PropertySource<?> ps : loader.load(file, new ClassPathResource(file))) {
				sources.addAfter("deploy", ps);
			}
		}
		return env;
	}

	private static void assertLettuceConditionMatches(StandardEnvironment env) {
		var condition = new RedisLettuceAutoConfiguration.RedisLettuceCondition();
		var ctx = new org.springframework.context.annotation.ConditionContext() {
			@Override public org.springframework.core.io.ResourceLoader getResourceLoader() { return null; }
			@Override public ClassLoader getClassLoader() { return getClass().getClassLoader(); }
			@Override public org.springframework.beans.factory.config.ConfigurableListableBeanFactory getBeanFactory() { return null; }
			@Override public org.springframework.beans.factory.support.BeanDefinitionRegistry getRegistry() { return null; }
			@Override public org.springframework.core.env.Environment getEnvironment() { return env; }
		};
		ConditionOutcome outcome = condition.getMatchOutcome(ctx,
			new StandardAnnotationMetadata(RedisLettuceAutoConfiguration.class));
		assertThat(outcome.isMatch()).as(outcome.getMessage()).isTrue();
	}
}
