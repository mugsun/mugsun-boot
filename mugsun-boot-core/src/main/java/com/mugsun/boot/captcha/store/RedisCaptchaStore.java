package com.mugsun.boot.captcha.store;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
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

	/** INCR 与 EXPIRE 在同一 Lua 脚本内执行；键缺少过期时间时补上，杜绝永久计数键 */
	private static final DefaultRedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
		"local n = redis.call('INCR', KEYS[1]) "
			+ "if n == 1 or redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
			+ "return n", Long.class);

	@Override
	public long increment(String key, long ttlSeconds) {
		Long n = redis.execute(INCR_WITH_TTL, List.of(key), String.valueOf(Math.max(1, ttlSeconds)));
		return n == null ? 0 : n;
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
