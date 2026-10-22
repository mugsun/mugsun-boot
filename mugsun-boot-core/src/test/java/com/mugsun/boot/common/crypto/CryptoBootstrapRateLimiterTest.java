package com.mugsun.boot.common.crypto;

import com.mugsun.boot.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 对真实 Redis（测试容器）跑 Lua：原子计数、首次设过期、TTL=-1 时补设 */
class CryptoBootstrapRateLimiterTest extends AbstractIntegrationTest {

	@Autowired
	StringRedisTemplate redis;

	@Test
	@DisplayName("按用户限流：超过上限被拒，其他用户不受影响；首次计数即带过期")
	void perUser() {
		CryptoBootstrapRateLimiter l = new CryptoBootstrapRateLimiter(redis, 3);
		String u1 = "rl-" + UUID.randomUUID();
		String u2 = "rl-" + UUID.randomUUID();
		assertThat(l.tryAcquire(u1)).isTrue();
		Long ttl = redis.getExpire(CryptoBootstrapRateLimiter.KEY_PREFIX + u1);
		assertThat(ttl).isBetween(1L, CryptoBootstrapRateLimiter.WINDOW_SECONDS);
		assertThat(l.tryAcquire(u1)).isTrue();
		assertThat(l.tryAcquire(u1)).isTrue();
		assertThat(l.tryAcquire(u1)).isFalse();
		assertThat(l.tryAcquire(u2)).isTrue();
	}

	@Test
	@DisplayName("上次设过期失败留下无过期计数（TTL=-1）：下一次调用补上过期时间")
	void repairsMissingExpire() {
		String u = "rl-" + UUID.randomUUID();
		String key = CryptoBootstrapRateLimiter.KEY_PREFIX + u;
		redis.opsForValue().set(key, "1");
		assertThat(redis.getExpire(key)).isEqualTo(-1L);
		new CryptoBootstrapRateLimiter(redis, 30).tryAcquire(u);
		assertThat(redis.getExpire(key)).isBetween(1L, CryptoBootstrapRateLimiter.WINDOW_SECONDS);
		assertThat(redis.opsForValue().get(key)).isEqualTo("2");
	}
}
