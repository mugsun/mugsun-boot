package com.mugsun.boot.captcha;

import com.mugsun.boot.captcha.store.CaptchaStoreConfig;
import com.mugsun.boot.captcha.store.MemoryCaptchaStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** prod profile 禁止 memory 存储（C6） */
class CaptchaStoreConfigTest {

	@Test
	void memoryStoreIsRejectedUnderProd() {
		CaptchaProperties props = new CaptchaProperties();
		props.setStore("memory");
		MockEnvironment prod = new MockEnvironment();
		prod.setActiveProfiles("prod");
		assertThatThrownBy(() -> new CaptchaStoreConfig().captchaStore(props, prod, null))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("prod");
	}

	@Test
	void memoryStoreAllowedOutsideProd() {
		CaptchaProperties props = new CaptchaProperties();
		props.setStore("memory");
		assertThat(new CaptchaStoreConfig().captchaStore(props, new MockEnvironment(), null))
			.isInstanceOf(MemoryCaptchaStore.class);
	}

	@Test
	void unknownStoreRejected() {
		CaptchaProperties props = new CaptchaProperties();
		props.setStore("mysql");
		assertThatThrownBy(() -> new CaptchaStoreConfig().captchaStore(props, new MockEnvironment(), null))
			.isInstanceOf(IllegalStateException.class);
	}
}
