package com.mugsun.boot.track.job;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;

import java.sql.SQLException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** app-statement-timeout 键绑定（中括号保留大小写与 . _）与 SQLState 提取；不依赖数据库 / Docker */
class TrackUvBackfillPropertiesBindingTest {

	@EnableConfigurationProperties(TrackUvBackfillProperties.class)
	static class Cfg {
	}

	@Test
	void yamlBracketKeysKeepCaseAndPunctuation() throws Exception {
		StandardEnvironment env = new StandardEnvironment();
		new YamlPropertySourceLoader().load("t", new ClassPathResource("track/uv-backfill-binding.yml"))
			.forEach(env.getPropertySources()::addFirst);
		TrackUvBackfillProperties p = Binder.get(env)
			.bind("mugsun.track.uv-backfill", TrackUvBackfillProperties.class).get();
		assertThat(p.getAppStatementTimeout()).containsEntry("MyApp_1", Duration.ofSeconds(600))
			.containsEntry("shop.web", Duration.ofMinutes(5));
	}

	@Test
	void propertyValuesBindingLikeTestPropertySource() {
		new ApplicationContextRunner().withUserConfiguration(Cfg.class)
			.withPropertyValues("mugsun.track.uv-backfill.app-statement-timeout[MyApp_1]=600s",
				"mugsun.track.uv-backfill.work-mem=128MB")
			.run(ctx -> {
				TrackUvBackfillProperties p = ctx.getBean(TrackUvBackfillProperties.class);
				assertThat(p.getAppStatementTimeout()).containsEntry("MyApp_1", Duration.ofSeconds(600));
				assertThat(p.getWorkMem()).isEqualTo("128MB");
			});
	}

	@Test
	void sqlStateOnlyForDatabaseErrors() {
		assertThat(TrackUvBackfillJob.sqlStateOf(new DataAccessResourceFailureException("x",
			new SQLException("deadlock", "40P01")))).isEqualTo("40P01");
		assertThat(TrackUvBackfillJob.sqlStateOf(new RuntimeException(new SQLException("conn", "08006"))))
			.isEqualTo("08006");
		assertThat(TrackUvBackfillJob.sqlStateOf(new RuntimeException(new SQLException("no state"))))
			.isEqualTo("UNKNOWN");
		assertThat(TrackUvBackfillJob.sqlStateOf(new IllegalStateException("bug"))).as("程序错误仍抛").isNull();
	}
}
