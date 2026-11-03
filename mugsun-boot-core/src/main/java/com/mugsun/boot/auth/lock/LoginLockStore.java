package com.mugsun.boot.auth.lock;

import java.time.Duration;
import java.util.Set;

/** 登录锁存储：redis（默认）或 memory（仅开发态降级）。 */
public interface LoginLockStore {

	boolean hasLock(String lockKey);

	Long getLockTtlSeconds(String lockKey);

	String getMeta(String lockKey);

	void setLock(String lockKey, String value, Duration ttlOrNull);

	void setMeta(String lockKey, String meta, Duration ttlOrNull);

	Long incrementFail(String failKey, Duration window);

	void deleteFail(String failKey);

	void deleteLock(String lockKey);

	void deleteMeta(String lockKey);

	void addIndex(String lockKey);

	void removeIndex(String lockKey);

	Set<String> indexMembers();
}
