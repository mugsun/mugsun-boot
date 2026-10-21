package com.mugsun.boot.config;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 非 dev/test/local 下未注入初始口令（危险配置）拒绝启动；白名单 profile 或已注入放行。
 */
class InitPasswordHardeningGuardTest {

	private static InitPasswordHardeningGuard guard(String profiles, boolean injected) {
		MockEnvironment env = new MockEnvironment();
		if (!profiles.isEmpty()) {
			env.setActiveProfiles(profiles.split(","));
		}
		if (injected) {
			env.setProperty("mugsun.security.init-password", "Injected-Pw-1");
		}
		return new InitPasswordHardeningGuard(env);
	}

	@ParameterizedTest
	@CsvSource({"''", "staging", "'dev,prod'", "prod", "PROD", "production", "'prod,lab'"})
	void refusesWithoutInjectedPassword(String profiles) {
		assertThatThrownBy(() -> guard(profiles, false).run())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("mugsun.security.init-password");
	}

	@ParameterizedTest
	@CsvSource({"dev,false", "test,false", "'',true", "prod,true"})
	void startsInDevOrWhenInjected(String profiles, boolean injected) {
		assertThatCode(() -> guard(profiles, injected).run()).doesNotThrowAnyException();
	}

	@org.junit.jupiter.api.Test
	void refusesWhenOnlyDefaultProfileIsProd() {
		MockEnvironment env = new MockEnvironment();
		env.setDefaultProfiles("prod");
		
		assertThatThrownBy(() -> new InitPasswordHardeningGuard(env).run()).isInstanceOf(IllegalStateException.class);
	}

	@org.junit.jupiter.api.Test
	void refusesWhenOnlyDefaultProfileIsDev() {
		MockEnvironment env = new MockEnvironment();
		env.setDefaultProfiles("dev");
		
		// 只看显式 active：只配 default=dev、active 为空按生产处理
		assertThatThrownBy(() -> new InitPasswordHardeningGuard(env).run()).isInstanceOf(IllegalStateException.class);
	}
}
