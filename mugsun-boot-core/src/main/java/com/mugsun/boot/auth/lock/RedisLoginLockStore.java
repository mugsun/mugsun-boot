package com.mugsun.boot.auth.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis 登录锁：失败计数 INCR+PEXPIRE 与达阈落锁走 Lua（#68）；
 * 索引为 ZSET（score=到期 epoch 毫秒），读写时清过期并封顶（#69）。
 */
final class RedisLoginLockStore implements LoginLockStore {

	private static final Logger log = LoggerFactory.getLogger(RedisLoginLockStore.class);

	private static final String FAIL_KEY = "mugsun:login:fail:";
	private static final String LOCK_KEY = "mugsun:login:lock:";
	private static final String META_KEY = "mugsun:login:lock-meta:";
	private static final String INDEX_KEY = "mugsun:login:lock-index";

	/** 永久锁索引分；远超常见 TTL，且在 double 精确整数范围内。 */
	static final long PERMANENT_SCORE_MS = 9_999_999_999_999L;

	/** 索引成员上限，防 demo 下 @ip 维度刷爆（#69）。 */
	static final int INDEX_MAX_SIZE = 10_000;

	/** INCR + PEXPIRE 同脚本，避免无 TTL 永久计数。 */
	private static final DefaultRedisScript<Long> INCR_EXPIRE_SCRIPT = new DefaultRedisScript<>(
		"local c = redis.call('INCR', KEYS[1]) "
			+ "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[1])) "
			+ "return c",
		Long.class);

	/**
	 * 达阈落锁一次完成：INCR+PEXPIRE；若 count>=max 则 SET LOCK/META、
	 * ZADD 索引（先清过期/迁移旧 SET）、DEL FAIL。
	 * KEYS: fail, lock, meta, index；ARGV: windowMs, maxFails, lockVal, meta,
	 * lockTtlMs(0=永久), nowMs, indexMax, indexMember, permanentScore。
	 */
	private static final DefaultRedisScript<Long> RECORD_FAIL_OR_LOCK_SCRIPT = new DefaultRedisScript<>(
		"local count = redis.call('INCR', KEYS[1]) "
			+ "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[1])) "
			+ "local maxFails = tonumber(ARGV[2]) "
			+ "if count < maxFails then return count end "
			+ "local ttl = tonumber(ARGV[5]) "
			+ "local now = tonumber(ARGV[6]) "
			+ "local perm = tonumber(ARGV[9]) "
			+ "if ttl > 0 then "
			+ "  redis.call('SET', KEYS[2], ARGV[3], 'PX', ttl) "
			+ "  redis.call('SET', KEYS[3], ARGV[4], 'PX', ttl) "
			+ "else "
			+ "  redis.call('SET', KEYS[2], ARGV[3]) "
			+ "  redis.call('SET', KEYS[3], ARGV[4]) "
			+ "end "
			+ "local raw = redis.call('TYPE', KEYS[4]) "
			+ "local t = type(raw) == 'table' and raw['ok'] or raw "
			+ "if t == 'set' then redis.call('DEL', KEYS[4]) end "
			+ "redis.call('ZREMRANGEBYSCORE', KEYS[4], '-inf', now) "
			+ "local score = (ttl > 0) and (now + ttl) or perm "
			+ "redis.call('ZADD', KEYS[4], score, ARGV[8]) "
			+ "local sz = redis.call('ZCARD', KEYS[4]) "
			+ "local maxSize = tonumber(ARGV[7]) "
			+ "if sz > maxSize then "
			+ "  redis.call('ZREMRANGEBYRANK', KEYS[4], 0, sz - maxSize - 1) "
			+ "end "
			+ "redis.call('DEL', KEYS[1]) "
			+ "return count",
		Long.class);

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
		Long count = redis.execute(INCR_EXPIRE_SCRIPT, List.of(FAIL_KEY + failKey),
			String.valueOf(window.toMillis()));
		return count;
	}

	@Override
	public long recordFailOrLock(String lockKey, Duration failWindow, int maxFails, String lockValue,
		String meta, Duration lockTtlOrNull) {
		long ttlMs = lockTtlOrNull == null ? 0L : Math.max(1L, lockTtlOrNull.toMillis());
		long now = System.currentTimeMillis();
		Long count = redis.execute(RECORD_FAIL_OR_LOCK_SCRIPT,
			List.of(FAIL_KEY + lockKey, LOCK_KEY + lockKey, META_KEY + lockKey, INDEX_KEY),
			String.valueOf(failWindow.toMillis()),
			String.valueOf(maxFails),
			lockValue,
			meta == null ? "" : meta,
			String.valueOf(ttlMs),
			String.valueOf(now),
			String.valueOf(INDEX_MAX_SIZE),
			lockKey,
			String.valueOf(PERMANENT_SCORE_MS));
		return count == null ? 0L : count;
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
	public void addIndex(String lockKey, Duration ttlOrNull) {
		migrateIndexIfNeeded();
		long now = System.currentTimeMillis();
		redis.opsForZSet().removeRangeByScore(INDEX_KEY, Double.NEGATIVE_INFINITY, now);
		double score = ttlOrNull == null ? PERMANENT_SCORE_MS : now + Math.max(1L, ttlOrNull.toMillis());
		redis.opsForZSet().add(INDEX_KEY, lockKey, score);
		trimIndexIfNeeded();
	}

	@Override
	public void removeIndex(String lockKey) {
		migrateIndexIfNeeded();
		redis.opsForZSet().remove(INDEX_KEY, lockKey);
	}

	@Override
	public Set<String> indexMembers() {
		migrateIndexIfNeeded();
		long now = System.currentTimeMillis();
		redis.opsForZSet().removeRangeByScore(INDEX_KEY, Double.NEGATIVE_INFINITY, now);
		trimIndexIfNeeded();
		Set<String> members = redis.opsForZSet().range(INDEX_KEY, 0, -1);
		return members == null ? Collections.emptySet() : members;
	}

	/** 旧版 INDEX 为 Set；类型不对则删除，交给 ZSET 重建（#69）。 */
	private void migrateIndexIfNeeded() {
		DataType type = redis.type(INDEX_KEY);
		if (type == DataType.SET) {
			redis.delete(INDEX_KEY);
			log.warn("login lock-index was Redis SET; deleted for ZSET migration (#69)");
		}
	}

	private void trimIndexIfNeeded() {
		Long size = redis.opsForZSet().zCard(INDEX_KEY);
		if (size == null || size <= INDEX_MAX_SIZE) {
			return;
		}
		long removeTo = size - INDEX_MAX_SIZE - 1;
		redis.opsForZSet().removeRange(INDEX_KEY, 0, removeTo);
		log.warn("login lock-index trimmed from {} to {} (#69)", size, INDEX_MAX_SIZE);
	}
}
