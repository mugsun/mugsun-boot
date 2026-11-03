package com.mugsun.boot.config;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/**
 * 开发态会话改内存：当 {@code mugsun.session.store=memory}（缺 Redis 自动降级写入）时，
 * 用 Sa-Token 默认实现覆盖 classpath 上的 Redis DAO。生产禁止 memory。
 */
@Configuration
@ConditionalOnProperty(name = "mugsun.session.store", havingValue = "memory")
public class SaTokenMemorySessionConfig {

	@Bean
	@Primary
	public SaTokenDao saTokenDaoMemory(Environment environment) {
		if (DeployProfiles.isProduction(environment)) {
			throw new IllegalStateException("mugsun.session.store=memory 不能用于生产，请提供 Redis");
		}
		return new SaTokenDaoDefaultImpl();
	}
}
