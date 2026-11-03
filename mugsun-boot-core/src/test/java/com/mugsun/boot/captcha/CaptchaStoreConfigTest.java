package com.mugsun.boot.captcha;

import com.mugsun.boot.captcha.store.CaptchaStoreConfig;
import com.mugsun.boot.captcha.store.MemoryCaptchaStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 生产禁止 memory 存储（与 DeployProfiles 对齐） */
class CaptchaStoreConfigTest {

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
	void memoryStoreIsRejectedUnderProd() {
		CaptchaProperties props = new CaptchaProperties();
		props.setStore("memory");
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod");
		assertThatThrownBy(() -> new CaptchaStoreConfig().captchaStore(props, prod, noRedis()))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("生产");
	}

	@Test
	void memoryStoreIsRejectedWhenActiveEmptyTreatedAsProd() {
		CaptchaProperties props = new CaptchaProperties();
		props.setStore("memory");
		assertThatThrownBy(() -> new CaptchaStoreConfig().captchaStore(props, new MockEnvironment(), noRedis()))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("生产");
	}

	@Test
	void memoryStoreIsRejectedUnderProductionAlias() {
		CaptchaProperties props = new CaptchaProperties();
		props.setStore("memory");
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("production");
		assertThatThrownBy(() -> new CaptchaStoreConfig().captchaStore(props, env, noRedis()))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("生产");
	}

	@Test
	void memoryStoreAllowedInDev() {
		CaptchaProperties props = new CaptchaProperties();
		props.setStore("memory");
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("dev");
		assertThat(new CaptchaStoreConfig().captchaStore(props, env, noRedis()))
			.isInstanceOf(MemoryCaptchaStore.class);
	}

	@Test
	void unknownStoreRejected() {
		CaptchaProperties props = new CaptchaProperties();
		props.setStore("mysql");
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("dev");
		assertThatThrownBy(() -> new CaptchaStoreConfig().captchaStore(props, env, noRedis()))
			.isInstanceOf(IllegalStateException.class);
	}
}
