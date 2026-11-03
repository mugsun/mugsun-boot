package com.mugsun.boot.auth.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class MemoryLoginLockStoreTest {

	@Test
	void lockExpireAndFailCounter() {
		AtomicLong now = new AtomicLong(1_000_000L);
		MemoryLoginLockStore store = new MemoryLoginLockStore(now::get, 100);
		assertThat(store.hasLock("a")).isFalse();
		store.setLock("a", "fail", Duration.ofMillis(50));
		assertThat(store.hasLock("a")).isTrue();
		assertThat(store.getLockTtlSeconds("a")).isGreaterThanOrEqualTo(0);
		now.addAndGet(60);
		assertThat(store.hasLock("a")).isFalse();

		assertThat(store.incrementFail("u", Duration.ofMinutes(1))).isEqualTo(1L);
		assertThat(store.incrementFail("u", Duration.ofMinutes(1))).isEqualTo(2L);
		store.deleteFail("u");
		assertThat(store.incrementFail("u", Duration.ofMinutes(1))).isEqualTo(1L);
	}

	@Test
	void permanentLock() {
		MemoryLoginLockStore store = new MemoryLoginLockStore();
		store.setLock("b", "manual", null);
		assertThat(store.hasLock("b")).isTrue();
		assertThat(store.getLockTtlSeconds("b")).isEqualTo(-1L);
	}

	@Test
	void recordFailOrLockIsAtomicAtThreshold() {
		AtomicLong now = new AtomicLong(1_000_000L);
		MemoryLoginLockStore store = new MemoryLoginLockStore(now::get, 100);
		Duration window = Duration.ofMinutes(5);
		assertThat(store.recordFailOrLock("t:u", window, 3, "fail", "过多", window)).isEqualTo(1L);
		assertThat(store.hasLock("t:u")).isFalse();
		assertThat(store.recordFailOrLock("t:u", window, 3, "fail", "过多", window)).isEqualTo(2L);
		assertThat(store.hasLock("t:u")).isFalse();
		assertThat(store.recordFailOrLock("t:u", window, 3, "fail", "过多", window)).isEqualTo(3L);
		assertThat(store.hasLock("t:u")).isTrue();
		assertThat(store.getMeta("t:u")).isEqualTo("过多");
		assertThat(store.indexMembers()).contains("t:u");
		// FAIL 已清，再失败从 1 起但已锁定无关
		assertThat(store.incrementFail("t:u", window)).isEqualTo(1L);
	}

	@Test
	void indexDropsExpiredMembersOnRead() {
		AtomicLong now = new AtomicLong(1_000_000L);
		MemoryLoginLockStore store = new MemoryLoginLockStore(now::get, 100);
		store.setLock("k1", "fail", Duration.ofMillis(100));
		store.addIndex("k1", Duration.ofMillis(100));
		store.setLock("k2", "manual", null);
		store.addIndex("k2", null);
		assertThat(store.indexMembers()).containsExactlyInAnyOrder("k1", "k2");
		now.addAndGet(200);
		assertThat(store.indexMembers()).containsExactly("k2");
		assertThat(store.hasLock("k1")).isFalse();
	}

	@Test
	void indexCappedDoesNotGrowUnbounded() {
		AtomicLong now = new AtomicLong(1_000_000L);
		int cap = 5;
		MemoryLoginLockStore store = new MemoryLoginLockStore(now::get, cap);
		for (int i = 0; i < 20; i++) {
			String key = "000000:anon@ip:1.2.3." + i;
			Duration ttl = Duration.ofMinutes(10);
			store.setLock(key, "fail", ttl);
			store.addIndex(key, ttl);
			now.addAndGet(1); // 不同到期分，淘汰最早
		}
		assertThat(store.indexSizeForTest()).isEqualTo(cap);
		assertThat(store.indexMembers()).hasSize(cap);
	}
}
