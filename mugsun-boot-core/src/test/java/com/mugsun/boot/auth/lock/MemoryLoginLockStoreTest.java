package com.mugsun.boot.auth.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class MemoryLoginLockStoreTest {

	@Test
	void lockExpireAndFailCounter() throws Exception {
		MemoryLoginLockStore store = new MemoryLoginLockStore();
		assertThat(store.hasLock("a")).isFalse();
		store.setLock("a", "fail", Duration.ofMillis(50));
		assertThat(store.hasLock("a")).isTrue();
		assertThat(store.getLockTtlSeconds("a")).isGreaterThanOrEqualTo(0);
		Thread.sleep(60);
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
}
