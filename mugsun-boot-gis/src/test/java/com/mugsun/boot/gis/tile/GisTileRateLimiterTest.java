package com.mugsun.boot.gis.tile;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class GisTileRateLimiterTest {

	private static GisTileProperties props(int perUser, int perIp) {
		GisTileProperties p = new GisTileProperties();
		p.setRateLimitPerUser(perUser);
		p.setRateLimitPerIp(perIp);
		return p;
	}

	@Test
	void limitsPerUserWithinWindowAndResetsNextMinute() {
		AtomicLong now = new AtomicLong(120_000);
		GisTileRateLimiter rl = new GisTileRateLimiter(props(3, 0), now::get);
		assertThat(rl.tryAcquire("u1", "1.1.1.1")).isTrue();
		assertThat(rl.tryAcquire("u1", "1.1.1.2")).isTrue();
		assertThat(rl.tryAcquire("u1", "1.1.1.3")).isTrue();
		assertThat(rl.tryAcquire("u1", "1.1.1.4")).as("同一用户换 IP 也限").isFalse();
		assertThat(rl.tryAcquire("u2", "1.1.1.1")).as("其他用户不受影响").isTrue();
		now.addAndGet(60_000);
		assertThat(rl.tryAcquire("u1", "1.1.1.1")).isTrue();
	}

	@Test
	void limitsPerIpAcrossUsers() {
		GisTileRateLimiter rl = new GisTileRateLimiter(props(0, 2), () -> 0L);
		assertThat(rl.tryAcquire("a", "9.9.9.9")).isTrue();
		assertThat(rl.tryAcquire("b", "9.9.9.9")).isTrue();
		assertThat(rl.tryAcquire("c", "9.9.9.9")).as("同一 IP 换用户也限").isFalse();
		assertThat(rl.tryAcquire("c", "8.8.8.8")).isTrue();
	}

	@Test
	void zeroMeansUnlimitedAndRetryAfterIsPositive() {
		GisTileRateLimiter rl = new GisTileRateLimiter(props(0, 0), () -> 59_500L);
		for (int i = 0; i < 10_000; i++) {
			assertThat(rl.tryAcquire("u", "ip")).isTrue();
		}
		assertThat(rl.retryAfterSeconds()).isBetween(1L, 60L);
	}

	@Test
	void defaultsAreEnabled() {
		GisTileProperties p = new GisTileProperties();
		assertThat(p.getRateLimitPerUser()).isPositive();
		assertThat(p.getRateLimitPerIp()).isPositive();
	}
}
