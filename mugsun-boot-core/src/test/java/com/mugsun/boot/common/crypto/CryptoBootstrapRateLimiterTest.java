package com.mugsun.boot.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CryptoBootstrapRateLimiterTest {

	@Test
	@DisplayName("按用户限流：同一用户超过每分钟上限被拒，其他用户不受影响；首次计数设 1 分钟过期")
	@SuppressWarnings("unchecked")
	void perUser() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		ValueOperations<String, String> ops = mock(ValueOperations.class);
		when(redis.opsForValue()).thenReturn(ops);
		Map<String, Long> counts = new HashMap<>();
		when(ops.increment(anyString())).thenAnswer(inv -> counts.merge(inv.getArgument(0), 1L, Long::sum));
		CryptoBootstrapRateLimiter l = new CryptoBootstrapRateLimiter(redis, 3);
		assertThat(l.tryAcquire("u1")).isTrue();
		assertThat(l.tryAcquire("u1")).isTrue();
		assertThat(l.tryAcquire("u1")).isTrue();
		assertThat(l.tryAcquire("u1")).isFalse();
		assertThat(l.tryAcquire("u2")).isTrue();
		verify(redis).expire(CryptoBootstrapRateLimiter.KEY_PREFIX + "u1", Duration.ofMinutes(1));
	}
}
