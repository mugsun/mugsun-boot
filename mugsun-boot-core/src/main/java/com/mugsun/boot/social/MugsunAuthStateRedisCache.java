package com.mugsun.boot.social;

import me.zhyd.oauth.cache.AuthStateCache;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * JustAuth state 缓存的 Redis 实现：state 落 Redis（复用 G34 StringRedisTemplate），
 * 集群安全地防 CSRF/重放。替换 JustAuth 默认的进程内内存缓存。默认 3 分钟过期。
 */
@Component
public class MugsunAuthStateRedisCache implements AuthStateCache {

	private static final String PREFIX = "mugsun:social:state:";
	private static final long DEFAULT_TIMEOUT_MS = Duration.ofMinutes(3).toMillis();

	private final StringRedisTemplate redis;

	public MugsunAuthStateRedisCache(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public void cache(String key, String value) {
		cache(key, value, DEFAULT_TIMEOUT_MS);
	}

	@Override
	public void cache(String key, String value, long timeout) {
		redis.opsForValue().set(PREFIX + key, value, timeout, TimeUnit.MILLISECONDS);
	}

	@Override
	public String get(String key) {
		return redis.opsForValue().get(PREFIX + key);
	}

	@Override
	public boolean containsKey(String key) {
		return Boolean.TRUE.equals(redis.hasKey(PREFIX + key));
	}
}
