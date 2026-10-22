package com.mugsun.boot.common.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 接口密钥启动下发的按用户限流：Redis 固定窗口（每用户每分钟 {@code per-user-per-minute} 次，默认 30），多实例共享。
 * Redis 出错时放行并打 WARN：下发本身要求已登录，限流失效不能让前端拿不到密钥。
 */
@Component
public class CryptoBootstrapRateLimiter {

	private static final Logger log = LoggerFactory.getLogger(CryptoBootstrapRateLimiter.class);
	static final String KEY_PREFIX = "mugsun:crypto:bootstrap:rl:";

	private final StringRedisTemplate redis;
	private final int perMinute;

	public CryptoBootstrapRateLimiter(StringRedisTemplate redis,
									  @Value("${mugsun.crypto.bootstrap.per-user-per-minute:30}") int perMinute) {
		this.redis = redis;
		this.perMinute = Math.max(1, perMinute);
	}

	public boolean tryAcquire(String userId) {
		String key = KEY_PREFIX + userId;
		try {
			Long n = redis.opsForValue().increment(key);
			if (n != null && n == 1L) {
				redis.expire(key, Duration.ofMinutes(1));
			}
			return n == null || n <= perMinute;
		} catch (RuntimeException e) {
			log.warn("接口密钥下发限流读写 Redis 失败，本次放行：{}", e.getClass().getSimpleName());
			return true;
		}
	}
}
