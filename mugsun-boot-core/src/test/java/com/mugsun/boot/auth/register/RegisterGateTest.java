package com.mugsun.boot.auth.register;

import com.mugsun.boot.common.env.DeployProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegisterGateTest {

	private static MockEnvironment env(String active) {
		MockEnvironment e = new MockEnvironment();
		if (active != null) {
			e.setProperty("spring.profiles.active", active);
		}
		return e;
	}

	@Test
	void defaultsFollowProdRule() {
		assertThat(RegisterGate.resolve(env(null))).isEqualTo(RegisterMode.OFF);
		assertThat(RegisterGate.resolve(env("prod"))).isEqualTo(RegisterMode.OFF);
		assertThat(RegisterGate.resolve(env("prod,demo"))).isEqualTo(RegisterMode.OFF);
		assertThat(RegisterGate.resolve(env("DEV,prod"))).isEqualTo(RegisterMode.OFF);
		assertThat(RegisterGate.resolve(env(" "))).isEqualTo(RegisterMode.OFF);
		assertThat(RegisterGate.resolve(env("dev"))).isEqualTo(RegisterMode.OPEN);
		assertThat(RegisterGate.resolve(env("test"))).isEqualTo(RegisterMode.OPEN);
		assertThat(RegisterGate.resolve(env("local,gis"))).isEqualTo(RegisterMode.OPEN);
	}

	@Test
	void defaultProfileOnlyCountsAsProd() {
		MockEnvironment e = env(null);
		e.setProperty("spring.profiles.default", "dev");
		e.setDefaultProfiles("dev");
		assertThat(RegisterGate.resolve(e)).isEqualTo(RegisterMode.OFF);
		assertThat(DeployProfiles.isNonProd(e)).isFalse();
	}

	@Test
	void explicitSettingsWin() {
		assertThat(RegisterGate.resolve(env("prod").withProperty("mugsun.auth.register.enabled", "true"))).isEqualTo(RegisterMode.OPEN);
		assertThat(RegisterGate.resolve(env("dev").withProperty("mugsun.auth.register.enabled", "false"))).isEqualTo(RegisterMode.OFF);
		assertThat(RegisterGate.resolve(env("prod").withProperty("mugsun.auth.register.mode", "open"))).isEqualTo(RegisterMode.OPEN);
		assertThat(RegisterGate.resolve(env("prod").withProperty("mugsun.auth.register.mode", "OPEN")
			.withProperty("mugsun.auth.register.enabled", "true"))).isEqualTo(RegisterMode.OPEN);
	}

	@Test
	void refusesToStart() {
		assertThatThrownBy(() -> RegisterGate.resolve(env("dev").withProperty("mugsun.auth.register.mode", "APPROVAL")))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("注册审核尚未实现");
		assertThatThrownBy(() -> RegisterGate.resolve(env("dev").withProperty("mugsun.auth.register.mode", "OFF")
			.withProperty("mugsun.auth.register.enabled", "true")))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("冲突");
		assertThatThrownBy(() -> RegisterGate.resolve(env("dev").withProperty("mugsun.auth.register.mode", "maybe")))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> RegisterGate.resolve(env("dev").withProperty("mugsun.auth.register.enabled", "yes")))
			.isInstanceOf(IllegalStateException.class);
	}
}
