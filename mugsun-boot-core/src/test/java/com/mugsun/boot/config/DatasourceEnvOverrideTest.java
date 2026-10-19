package com.mugsun.boot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** 本地开发：数据库 / Redis 地址端口可经环境变量覆盖（与 docker-compose 的 MUGSUN_DB_PORT / MUGSUN_REDIS_PORT 同名）。 */
class DatasourceEnvOverrideTest {

	private static StandardEnvironment load(Map<String, Object> env) throws Exception {
		StandardEnvironment e = new StandardEnvironment();
		e.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		e.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		e.getPropertySources().addFirst(new MapPropertySource("env", env));
		new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"))
			.forEach(ps -> e.getPropertySources().addLast(ps));
		return e;
	}

	@Test
	void defaultsUnchanged() throws Exception {
		StandardEnvironment e = load(Map.of());
		assertThat(e.getProperty("mybatis-flex.datasource.primary.url")).isEqualTo("jdbc:postgresql://localhost:5432/mugsun");
		assertThat(e.getProperty("mybatis-flex.datasource.primary.username")).isEqualTo("mugsun");
		assertThat(e.getProperty("mybatis-flex.datasource.primary.password")).isEqualTo("mugsun");
		assertThat(e.getProperty("spring.data.redis.host")).isEqualTo("localhost");
		assertThat(e.getProperty("spring.data.redis.port")).isEqualTo("6379");
		assertThat(e.getProperty("jetcache.remote.default.uri")).isEqualTo("redis://localhost:6379/3");
	}

	@Test
	void hostPortOverridable() throws Exception {
		StandardEnvironment e = load(Map.of("MUGSUN_DB_HOST", "db.local", "MUGSUN_DB_PORT", "55432",
				"MUGSUN_DB_NAME", "dev", "MUGSUN_DB_PASSWORD", "s3cret", "MUGSUN_REDIS_HOST", "r.local",
				"MUGSUN_REDIS_PORT", "56379"));
		assertThat(e.getProperty("mybatis-flex.datasource.primary.url")).isEqualTo("jdbc:postgresql://db.local:55432/dev");
		assertThat(e.getProperty("mybatis-flex.datasource.primary.password")).isEqualTo("s3cret");
		assertThat(e.getProperty("spring.data.redis.host")).isEqualTo("r.local");
		assertThat(e.getProperty("spring.data.redis.port")).isEqualTo("56379");
		assertThat(e.getProperty("jetcache.remote.default.uri")).isEqualTo("redis://r.local:56379/3");
	}

	@Test
	void fullUrlWins() throws Exception {
		StandardEnvironment e = load(Map.of("MUGSUN_DB_URL", "jdbc:postgresql://x:1/y", "MUGSUN_DB_PORT", "55432"));
		assertThat(e.getProperty("mybatis-flex.datasource.primary.url")).isEqualTo("jdbc:postgresql://x:1/y");
	}
}
