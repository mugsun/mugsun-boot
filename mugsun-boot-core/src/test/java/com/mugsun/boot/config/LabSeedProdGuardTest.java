package com.mugsun.boot.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * prod 下 seed-fronttest=true 拒绝启动；prod+false、dev+true 放行。
 */
class LabSeedProdGuardTest {

	private static LabSeedProdGuard guard(String profile, String value) {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles(profile);
		if (value != null) {
			env.setProperty(LabSeedProdGuard.KEY, value);
		}
		return new LabSeedProdGuard(env);
	}

	@Test
	void prodWithSeedTrueRefusesToStart() {
		assertThatThrownBy(() -> guard("prod", "true").run())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("mugsun.lab.seed-fronttest");
	}

	@Test
	void prodWithSeedFalseStarts() {
		assertThatCode(() -> guard("prod", "false").run()).doesNotThrowAnyException();
	}

	@Test
	void prodWithoutSeedKeyStarts() {
		assertThatCode(() -> guard("prod", null).run()).doesNotThrowAnyException();
	}

	@Test
	void devWithSeedTrueStarts() {
		assertThatCode(() -> guard("dev", "true").run()).doesNotThrowAnyException();
	}
}
