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

	/**
	 * 失败计数 +1 并刷新窗口 TTL。Redis 实现须 INCR+PEXPIRE 同脚本，
	 * 避免进程中断留下无 TTL 的永久计数（#68）。
	 */
	Long incrementFail(String failKey, Duration window);

	/**
	 * 原子：失败计数 +1（带窗口 TTL）；达阈值则写 LOCK/META、索引、删 FAIL。
	 * 把「失败达阈即锁」沉到 store，避免 service 多轮往返竞态（#68）。
	 *
	 * @return 本次自增后的失败次数（达阈并删 FAIL 前的值）
	 */
	long recordFailOrLock(String lockKey, Duration failWindow, int maxFails, String lockValue,
		String meta, Duration lockTtlOrNull);

	void deleteFail(String failKey);

	void deleteLock(String lockKey);

	void deleteMeta(String lockKey);

	/** 写入锁定索引；ttl 为空表示永久锁。ZSET score=到期毫秒，写入时清过期（#69）。 */
	void addIndex(String lockKey, Duration ttlOrNull);

	void removeIndex(String lockKey);

	/** 当前索引成员；读取时顺带清过期成员，并受集合大小上限约束（#69）。 */
	Set<String> indexMembers();
}
