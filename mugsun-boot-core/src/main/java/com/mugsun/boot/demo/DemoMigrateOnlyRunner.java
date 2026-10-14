package com.mugsun.boot.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 演示环境的 owner 一次性迁移：用 owner 账号、非 demo profile 启动并带 {@code --mugsun.demo.migrate-only=true}，
 * 正常执行 Flyway（业务库 + 埋点库）和全部启动期写入（建列、播种），应用就绪后立即退出（退出码 0）。
 * <p>CommandLineRunner 都在 ApplicationReadyEvent 之前执行完，所以退出时启动期写入已全部完成。
 */
@Component
@ConditionalOnProperty(name = DemoConstants.MIGRATE_ONLY_PROPERTY, havingValue = "true")
public class DemoMigrateOnlyRunner {

	private static final Logger log = LoggerFactory.getLogger(DemoMigrateOnlyRunner.class);

	private final ApplicationContext context;
	private final boolean exit;

	public DemoMigrateOnlyRunner(ApplicationContext context,
								 @org.springframework.beans.factory.annotation.Value("${mugsun.demo.migrate-only-exit:true}") boolean exit) {
		this.context = context;
		this.exit = exit;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void done() {
		log.info("[demo] owner 迁移与启动期初始化已完成");
		if (exit) {
			System.exit(SpringApplication.exit(context, () -> 0));
		}
	}
}
