package com.mugsun.boot.auth.lock;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class RedisLoginLockStore implements LoginLockStore {

	private static final String FAIL_KEY = "mugsun:login:fail:";
	private static final String LOCK_KEY = "mugsun:login:lock:";
	private static final String META_KEY = "mugsun:login:lock-meta:";
	private static final String INDEX_KEY = "mugsun:login:lock-index";

	private final StringRedisTemplate redis;

	RedisLoginLockStore(StringRedisTemplate redis) {
		this.redis = redis;
	}

	@Override
	public boolean hasLock(String lockKey) {
		return Boolean.TRUE.equals(redis.hasKey(LOCK_KEY + lockKey));
	}

	@Override
	public Long getLockTtlSeconds(String lockKey) {
		return redis.getExpire(LOCK_KEY + lockKey, TimeUnit.SECONDS);
	}

	@Override
	public String getMeta(String lockKey) {
		return redis.opsForValue().get(META_KEY + lockKey);
	}

	@Override
	public void setLock(String lockKey, String value, Duration ttlOrNull) {
		if (ttlOrNull == null) {
			redis.opsForValue().set(LOCK_KEY + lockKey, value);
		} else {
			redis.opsForValue().set(LOCK_KEY + lockKey, value, ttlOrNull);
		}
	}

	@Override
	public void setMeta(String lockKey, String meta, Duration ttlOrNull) {
		if (ttlOrNull == null) {
			redis.opsForValue().set(META_KEY + lockKey, meta);
		} else {
			redis.opsForValue().set(META_KEY + lockKey, meta, ttlOrNull);
		}
	}

	@Override
	public Long incrementFail(String failKey, Duration window) {
		Long count = redis.opsForValue().increment(FAIL_KEY + failKey);
		redis.expire(FAIL_KEY + failKey, window);
		return count;
	}

	@Override
	public void deleteFail(String failKey) {
		redis.delete(FAIL_KEY + failKey);
	}

	@Override
	public void deleteLock(String lockKey) {
		redis.delete(LOCK_KEY + lockKey);
	}

	@Override
	public void deleteMeta(String lockKey) {
		redis.delete(META_KEY + lockKey);
	}

	@Override
	public void addIndex(String lockKey) {
		redis.opsForSet().add(INDEX_KEY, lockKey);
	}

	@Override
	public void removeIndex(String lockKey) {
		redis.opsForSet().remove(INDEX_KEY, lockKey);
	}

	@Override
	public Set<String> indexMembers() {
		Set<String> members = redis.opsForSet().members(INDEX_KEY);
		return members == null ? Collections.emptySet() : members;
	}
}
