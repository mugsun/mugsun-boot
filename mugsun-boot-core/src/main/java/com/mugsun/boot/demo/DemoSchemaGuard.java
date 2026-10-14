package com.mugsun.boot.demo;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.ValidateResult;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.stream.Collectors;

/**
 * 只读演示模式启动校验：demo 下不跑 Flyway（应用只持只读账号），改为只读校验
 * flyway_schema_history 与 jar 内迁移是否一致——有未执行（pending）、校验和不符或缺失的迁移就拒绝启动，
 * 提示先用 owner 账号执行一次 {@code --mugsun.demo.migrate-only=true}。
 * <p>Flyway validate 只读历史表、不加锁、不建表，只读账号即可。
 */
@Component
@ConditionalOnProperty(name = DemoConstants.ENABLED_PROPERTY, havingValue = "true")
public class DemoSchemaGuard {

	private static final Logger log = LoggerFactory.getLogger(DemoSchemaGuard.class);

	public DemoSchemaGuard(DataSource dataSource, Environment env) {
		if (env.getProperty("spring.flyway.enabled", Boolean.class, true)) {
			throw new IllegalStateException("[demo] 演示模式要求 spring.flyway.enabled=false（迁移由 owner 账号执行）");
		}
		if (env.getProperty(DemoConstants.MIGRATE_ONLY_PROPERTY, Boolean.class, false)) {
			throw new IllegalStateException("[demo] mugsun.demo.migrate-only 必须用非 demo profile、owner 账号执行");
		}
		FluentConfiguration cfg = Flyway.configure()
			.dataSource(dataSource)
			.locations(env.getProperty("spring.flyway.locations", String[].class, new String[]{"classpath:db/migration"}))
			.baselineVersion(env.getProperty("spring.flyway.baseline-version", "0"))
			.placeholderReplacement(false);
		String schema = env.getProperty("mugsun.db.default-schema");
		if (StringUtils.hasText(schema)) {
			cfg.schemas(schema);
		}
		validate(cfg.load(), "业务库");
	}

	/** 校验一个库的迁移历史，不一致即抛出（拒绝启动） */
	public static void validate(Flyway flyway, String label) {
		ValidateResult result = flyway.validateWithResult();
		var current = flyway.info().current();
		String version = current == null ? "（空）" : current.getVersion().getVersion();
		if (!result.validationSuccessful) {
			String detail = result.invalidMigrations.stream()
				.map(m -> m.version + " " + m.description + "：" + m.errorDetails.errorMessage)
				.limit(10)
				.collect(Collectors.joining("；"));
			throw new IllegalStateException("[demo] " + label + "迁移版本与程序不一致（库内当前 " + version
				+ "），拒绝启动。请先用 owner 账号执行 --mugsun.demo.migrate-only=true。详情：" + detail);
		}
		log.info("[demo] {}迁移版本校验通过：{}", label, version);
	}
}
