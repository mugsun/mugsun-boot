package com.mugsun.boot.captcha.store;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Redis 存储：take 用 GETDEL（Redis ≥ 6.2）保证并发下仅一方取到值 */
public class RedisCaptchaStore implements CaptchaStore {

	private final StringRedisTemplate redis;

	public RedisCaptchaStore(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public void put(String key, String value, long ttlSeconds) {
		redis.opsForValue().set(key, value, Duration.ofSeconds(Math.max(1, ttlSeconds)));
	}

	@Override
	public String take(String key) {
		return redis.opsForValue().getAndDelete(key);
	}

	@Override
	public String peek(String key) {
		return redis.opsForValue().get(key);
	}

	@Override
	public long increment(String key, long ttlSeconds) {
		Long n = redis.opsForValue().increment(key);
		long v = n == null ? 0 : n;
		if (v == 1) {
			redis.expire(key, Duration.ofSeconds(Math.max(1, ttlSeconds)));
		}
		return v;
	}

	@Override
	public long ttlSeconds(String key) {
		Long t = redis.getExpire(key, TimeUnit.SECONDS);
		return t == null || t < 0 ? 0 : t;
	}

	@Override
	public void delete(String key) {
		redis.delete(key);
	}
}
