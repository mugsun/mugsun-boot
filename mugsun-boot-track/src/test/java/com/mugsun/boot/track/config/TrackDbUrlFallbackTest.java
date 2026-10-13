package com.mugsun.boot.track.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** 埋点库 URL 回落顺序：TRACK_JDBC_URL → MUGSUN_DB_URL → MUGSUN_DB_HOST/PORT。 */
class TrackDbUrlFallbackTest {

	private static final String KEY = "mybatis-flex.datasource.track.url";

	private static String url(Map<String, Object> env) throws Exception {
		StandardEnvironment e = new StandardEnvironment();
		e.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		e.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		e.getPropertySources().addFirst(new MapPropertySource("env", env));
		new YamlPropertySourceLoader().load("track", new ClassPathResource("mugsun-module-track.yml"))
			.forEach(ps -> e.getPropertySources().addLast(ps));
		new TrackDbUrlEnvironmentPostProcessor().postProcessEnvironment(e, null);
		return e.getProperty(KEY);
	}

	@Test
	void defaultIsLocalhost() throws Exception {
		assertThat(url(Map.of())).isEqualTo("jdbc:postgresql://localhost:5432/mugsun_track");
	}

	@Test
	void hostPortUsedWhenNoUrl() throws Exception {
		assertThat(url(Map.of("MUGSUN_DB_HOST", "db.local", "MUGSUN_DB_PORT", "55432")))
			.isEqualTo("jdbc:postgresql://db.local:55432/mugsun_track");
	}

	@Test
	void mainUrlFollowedWithTrackDatabase() throws Exception {
		assertThat(url(Map.of("MUGSUN_DB_URL", "jdbc:postgresql://pg.prod:6543/biz?sslmode=require",
				"MUGSUN_DB_HOST", "ignored", "MUGSUN_DB_PORT", "1")))
			.isEqualTo("jdbc:postgresql://pg.prod:6543/mugsun_track?sslmode=require");
	}

	@Test
	void trackUrlWins() throws Exception {
		assertThat(url(Map.of("TRACK_JDBC_URL", "jdbc:postgresql://t:1/t", "MUGSUN_DB_URL", "jdbc:postgresql://x:2/y")))
			.isEqualTo("jdbc:postgresql://t:1/t");
	}

	@Test
	void unparseableMainUrlFallsBackToHostPort() throws Exception {
		assertThat(url(Map.of("MUGSUN_DB_URL", "garbage", "MUGSUN_DB_HOST", "h")))
			.isEqualTo("jdbc:postgresql://h:5432/mugsun_track");
	}

	@Test
	void deriveHandlesMultiHostAndNoParams() {
		assertThat(TrackDbUrlEnvironmentPostProcessor.derive("jdbc:postgresql://a:1,b:2/biz"))
			.isEqualTo("jdbc:postgresql://a:1,b:2/mugsun_track");
		assertThat(TrackDbUrlEnvironmentPostProcessor.derive("jdbc:postgresql://a:1")).isNull();
	}
}
