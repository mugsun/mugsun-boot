package com.mugsun.boot.auth.lock;

import com.mugsun.boot.config.DeployProfiles;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 按 mugsun.login-lock.store 选择登录锁存储；prod 禁止 memory。 */
@Configuration
public class LoginLockStoreConfig {

	@Bean
	public LoginLockStore loginLockStore(Environment env, ObjectProvider<StringRedisTemplate> redis) {
		String kind = env.getProperty("mugsun.login-lock.store", "redis").trim().toLowerCase();
		if ("memory".equals(kind)) {
			if (DeployProfiles.isProduction(env)) {
				throw new IllegalStateException("mugsun.login-lock.store=memory 不能用于生产，请改为 redis");
			}
			return new MemoryLoginLockStore();
		}
		if (!"redis".equals(kind)) {
			throw new IllegalStateException("mugsun.login-lock.store 只支持 redis 或 memory：" + kind);
		}
		StringRedisTemplate template = redis.getIfAvailable();
		if (template == null) {
			throw new IllegalStateException("mugsun.login-lock.store=redis 需要 StringRedisTemplate，请提供 Redis");
		}
		return new RedisLoginLockStore(template);
	}
}
