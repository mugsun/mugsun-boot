package com.mugsun.boot.captcha.store;

import com.mugsun.boot.captcha.CaptchaProperties;
import com.mugsun.boot.config.DeployProfiles;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 按 mugsun.captcha.store 选择存储；生产禁止 memory（多实例下会失去一次性与限流） */
@Configuration
public class CaptchaStoreConfig {

	@Bean
	public CaptchaStore captchaStore(CaptchaProperties props, Environment env,
			ObjectProvider<StringRedisTemplate> redis) {
		String kind = props.getStore() == null ? "redis" : props.getStore().trim().toLowerCase();
		if ("memory".equals(kind)) {
			if (DeployProfiles.isProduction(env)) {
				throw new IllegalStateException("mugsun.captcha.store=memory 不能用于生产，请改为 redis");
			}
			return new MemoryCaptchaStore();
		}
		if (!"redis".equals(kind)) {
			throw new IllegalStateException("mugsun.captcha.store 只支持 redis 或 memory：" + kind);
		}
		StringRedisTemplate template = redis.getIfAvailable();
		if (template == null) {
			throw new IllegalStateException("mugsun.captcha.store=redis 需要 StringRedisTemplate，请提供 Redis");
		}
		return new RedisCaptchaStore(template);
	}
}
