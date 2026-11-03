package com.mugsun.boot.auth.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

class LoginLockStoreConfigTest {

	private static ObjectProvider<StringRedisTemplate> noRedis() {
		return new ObjectProvider<>() {
			@Override
			public StringRedisTemplate getObject() {
				return null;
			}

			@Override
			public StringRedisTemplate getObject(Object... args) {
				return null;
			}

			@Override
			public StringRedisTemplate getIfAvailable() {
				return null;
			}

			@Override
			public StringRedisTemplate getIfUnique() {
				return null;
			}
		};
	}

	@Test
	void memoryRejectedInProduction() {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("prod");
		env.setProperty("mugsun.login-lock.store", "memory");
		assertThatThrownBy(() -> new LoginLockStoreConfig().loginLockStore(env, noRedis()))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("生产");
	}

	@Test
	void memoryAllowedInDev() {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("dev");
		env.setProperty("mugsun.login-lock.store", "memory");
		assertThat(new LoginLockStoreConfig().loginLockStore(env, noRedis()))
			.isInstanceOf(MemoryLoginLockStore.class);
	}
}
