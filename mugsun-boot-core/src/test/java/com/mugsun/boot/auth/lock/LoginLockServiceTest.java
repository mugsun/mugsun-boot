package com.mugsun.boot.auth.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.mugsun.boot.auth.LoginLockService;
import com.mugsun.boot.security.SecurityPolicyService;
import com.mugsun.core.tool.exception.ServiceException;

class LoginLockServiceTest {

	private SecurityPolicyService policy;
	private MemoryLoginLockStore store;
	private LoginLockService service;
	private final AtomicLong now = new AtomicLong(1_700_000_000_000L);

	@BeforeEach
	void setUp() {
		policy = mock(SecurityPolicyService.class);
		when(policy.getLoginFailMax()).thenReturn(3);
		when(policy.getLockMinutes()).thenReturn(5);
		store = new MemoryLoginLockStore(now::get, 8);
		service = new LoginLockService(policy, store);
		ReflectionTestUtils.setField(service, "demoMode", true);
	}

	@Test
	void recordFailLocksAtThresholdViaStore() {
		String key = service.keyForLogin("000000", "admin", "10.0.0.1");
		service.recordFail(key);
		service.recordFail(key);
		assertThat(service.isLocked(key)).isFalse();
		service.recordFail(key);
		assertThat(service.isLocked(key)).isTrue();
		assertThatThrownBy(() -> service.assertNotLocked(key))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("锁定");
	}

	@Test
	void demoIpIndexDoesNotGrowPastCap() {
		for (int i = 0; i < 30; i++) {
			String key = service.keyForLogin("000000", "guest", "1.1.1." + i);
			service.recordFail(key);
			service.recordFail(key);
			service.recordFail(key);
			now.addAndGet(1);
		}
		assertThat(store.indexSizeForTest()).isLessThanOrEqualTo(8);
		List<Map<String, Object>> rows = service.list("000000");
		assertThat(rows.size()).isLessThanOrEqualTo(8);
	}

	@Test
	void listPurgesExpiredIndexMembers() {
		String key = "000000:alice";
		service.manualLock(key, "临时", 1, "op");
		assertThat(service.list("000000")).hasSize(1);
		now.addAndGet(Duration.ofMinutes(2).toMillis());
		assertThat(service.list("000000")).isEmpty();
		assertThat(store.indexMembers()).doesNotContain(key);
	}

	@Test
	void clearRemovesLockAndIndex() {
		String key = "000000:bob";
		service.manualLock(key, "手动", null, "op");
		assertThat(service.isLocked(key)).isTrue();
		service.clear(key);
		assertThat(service.isLocked(key)).isFalse();
		assertThat(store.indexMembers()).doesNotContain(key);
	}
}
