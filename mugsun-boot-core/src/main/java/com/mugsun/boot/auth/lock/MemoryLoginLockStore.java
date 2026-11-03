package com.mugsun.boot.auth.lock;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 进程内登录锁：仅开发态缺 Redis 降级使用。带过期时间的条目按 deadline 判定，重启即清空。
 */
final class MemoryLoginLockStore implements LoginLockStore {

	private final Map<String, Entry> locks = new ConcurrentHashMap<>();
	private final Map<String, Entry> metas = new ConcurrentHashMap<>();
	private final Map<String, Counter> fails = new ConcurrentHashMap<>();
	private final Set<String> index = ConcurrentHashMap.newKeySet();

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
		return Math.max(0L, (e.deadlineMs - System.currentTimeMillis() + 999) / 1000);
	}

	@Override
	public String getMeta(String lockKey) {
		Entry e = alive(metas, lockKey);
		return e == null ? null : e.value;
	}

	@Override
	public void setLock(String lockKey, String value, Duration ttlOrNull) {
		locks.put(lockKey, Entry.of(value, ttlOrNull));
	}

	@Override
	public void setMeta(String lockKey, String meta, Duration ttlOrNull) {
		metas.put(lockKey, Entry.of(meta, ttlOrNull));
	}

	@Override
	public Long incrementFail(String failKey, Duration window) {
		long now = System.currentTimeMillis();
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
	public void addIndex(String lockKey) {
		index.add(lockKey);
	}

	@Override
	public void removeIndex(String lockKey) {
		index.remove(lockKey);
	}

	@Override
	public Set<String> indexMembers() {
		return index.stream().collect(Collectors.toSet());
	}

	private static Entry alive(Map<String, Entry> map, String key) {
		Entry e = map.get(key);
		if (e == null) {
			return null;
		}
		if (e.deadlineMs >= 0 && e.deadlineMs < System.currentTimeMillis()) {
			map.remove(key, e);
			return null;
		}
		return e;
	}

	private record Entry(String value, long deadlineMs) {
		static Entry of(String value, Duration ttlOrNull) {
			long deadline = ttlOrNull == null ? -1L : System.currentTimeMillis() + ttlOrNull.toMillis();
			return new Entry(value, deadline);
		}
	}

	private record Counter(long count, long deadlineMs) {
	}
}
