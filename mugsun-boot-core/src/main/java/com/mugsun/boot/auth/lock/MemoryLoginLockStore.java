package com.mugsun.boot.auth.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * 进程内登录锁：仅开发态缺 Redis 降级使用。带过期时间的条目按 deadline 判定，重启即清空。
 * 失败达阈落锁与索引清理语义与 {@link RedisLoginLockStore} 对齐（#68 / #69）。
 */
final class MemoryLoginLockStore implements LoginLockStore {

	private static final Logger log = LoggerFactory.getLogger(MemoryLoginLockStore.class);

	private final Map<String, Entry> locks = new ConcurrentHashMap<>();
	private final Map<String, Entry> metas = new ConcurrentHashMap<>();
	private final Map<String, Counter> fails = new ConcurrentHashMap<>();
	/** member -> 到期 epoch 毫秒；负数表示永久。 */
	private final Map<String, Long> index = new ConcurrentHashMap<>();
	private final LongSupplier clock;
	private final int indexMaxSize;

	MemoryLoginLockStore() {
		this(System::currentTimeMillis, RedisLoginLockStore.INDEX_MAX_SIZE);
	}

	/** 测试用：可注入时钟与索引上限。 */
	MemoryLoginLockStore(LongSupplier clock, int indexMaxSize) {
		this.clock = clock;
		this.indexMaxSize = Math.max(1, indexMaxSize);
	}

	@Override
	public boolean hasLock(String lockKey) {
		return alive(locks, lockKey) != null;
	}

	@Override
	public Long getLockTtlSeconds(String lockKey) {
		Entry e = alive(locks, lockKey);
		if (e == null) {
			return -2L;
		}
		if (e.deadlineMs < 0) {
			return -1L;
		}
		return Math.max(0L, (e.deadlineMs - clock.getAsLong() + 999) / 1000);
	}

	@Override
	public String getMeta(String lockKey) {
		Entry e = alive(metas, lockKey);
		return e == null ? null : e.value;
	}

	@Override
	public void setLock(String lockKey, String value, Duration ttlOrNull) {
		locks.put(lockKey, Entry.of(value, ttlOrNull, clock.getAsLong()));
	}

	@Override
	public void setMeta(String lockKey, String meta, Duration ttlOrNull) {
		metas.put(lockKey, Entry.of(meta, ttlOrNull, clock.getAsLong()));
	}

	@Override
	public Long incrementFail(String failKey, Duration window) {
		long now = clock.getAsLong();
		long deadline = now + window.toMillis();
		Counter next = fails.compute(failKey, (k, cur) -> {
			if (cur == null || cur.deadlineMs < now) {
				return new Counter(1, deadline);
			}
			return new Counter(cur.count + 1, deadline);
		});
		return next.count;
	}

	@Override
	public long recordFailOrLock(String lockKey, Duration failWindow, int maxFails, String lockValue,
		String meta, Duration lockTtlOrNull) {
		// 单 store 实例上串行化达阈路径，与 Redis Lua 同语义
		synchronized (this) {
			long count = incrementFail(lockKey, failWindow);
			if (count >= maxFails) {
				setLock(lockKey, lockValue, lockTtlOrNull);
				setMeta(lockKey, meta, lockTtlOrNull);
				addIndex(lockKey, lockTtlOrNull);
				deleteFail(lockKey);
			}
			return count;
		}
	}

	@Override
	public void deleteFail(String failKey) {
		fails.remove(failKey);
	}

	@Override
	public void deleteLock(String lockKey) {
		locks.remove(lockKey);
	}

	@Override
	public void deleteMeta(String lockKey) {
		metas.remove(lockKey);
	}

	@Override
	public void addIndex(String lockKey, Duration ttlOrNull) {
		purgeExpiredIndex();
		long now = clock.getAsLong();
		long score = ttlOrNull == null ? -1L : now + Math.max(1L, ttlOrNull.toMillis());
		index.put(lockKey, score);
		trimIndexIfNeeded();
	}

	@Override
	public void removeIndex(String lockKey) {
		index.remove(lockKey);
	}

	@Override
	public Set<String> indexMembers() {
		purgeExpiredIndex();
		trimIndexIfNeeded();
		return index.keySet().stream().collect(Collectors.toSet());
	}

	/** 测试可见：当前索引大小（含未读清前的）。 */
	int indexSizeForTest() {
		purgeExpiredIndex();
		return index.size();
	}

	private void purgeExpiredIndex() {
		long now = clock.getAsLong();
		index.entrySet().removeIf(e -> {
			long score = e.getValue();
			if (score < 0) {
				return false;
			}
			if (score > now) {
				return false;
			}
			// 锁已过期：顺带丢掉 lock/meta，与 list 清理一致
			locks.remove(e.getKey());
			metas.remove(e.getKey());
			return true;
		});
	}

	private void trimIndexIfNeeded() {
		if (index.size() <= indexMaxSize) {
			return;
		}
		// 先按到期时间升序（永久排最后），超限淘汰最早到期
		LinkedHashMap<String, Long> ordered = index.entrySet().stream()
			.sorted((a, b) -> {
				long sa = a.getValue() < 0 ? RedisLoginLockStore.PERMANENT_SCORE_MS : a.getValue();
				long sb = b.getValue() < 0 ? RedisLoginLockStore.PERMANENT_SCORE_MS : b.getValue();
				return Long.compare(sa, sb);
			})
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (x, y) -> x, LinkedHashMap::new));
		int remove = index.size() - indexMaxSize;
		int i = 0;
		for (String key : ordered.keySet()) {
			if (i++ >= remove) {
				break;
			}
			index.remove(key);
		}
		log.warn("login lock-index trimmed to {} (#69)", indexMaxSize);
	}

	private Entry alive(Map<String, Entry> map, String key) {
		Entry e = map.get(key);
		if (e == null) {
			return null;
		}
		if (e.deadlineMs >= 0 && e.deadlineMs < clock.getAsLong()) {
			map.remove(key, e);
			return null;
		}
		return e;
	}

	private record Entry(String value, long deadlineMs) {
		static Entry of(String value, Duration ttlOrNull, long now) {
			long deadline = ttlOrNull == null ? -1L : now + ttlOrNull.toMillis();
			return new Entry(value, deadline);
		}
	}

	private record Counter(long count, long deadlineMs) {
	}
}
