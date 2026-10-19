package com.mugsun.boot.captcha.store;

import com.mugsun.boot.captcha.CaptchaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 按 mugsun.captcha.store 选择存储；prod profile 下禁止 memory（多实例下会失去一次性与限流） */
@Configuration
public class CaptchaStoreConfig {

	@Bean
	public CaptchaStore captchaStore(CaptchaProperties props, Environment env, StringRedisTemplate redis) {
		String kind = props.getStore() == null ? "redis" : props.getStore().trim().toLowerCase();
		if ("memory".equals(kind)) {
			if (env.acceptsProfiles(Profiles.of("prod"))) {
				throw new IllegalStateException("mugsun.captcha.store=memory 不能用于 prod profile，请改为 redis");
			}
			return new MemoryCaptchaStore();
		}
		if (!"redis".equals(kind)) {
			throw new IllegalStateException("mugsun.captcha.store 只支持 redis 或 memory：" + kind);
		}
		return new RedisCaptchaStore(redis);
	}
}
