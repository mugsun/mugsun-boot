package com.mugsun.boot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只看显式 active：含 dev/test/local（trim+小写）且不含 prod 才算非生产；其余一律按生产。
 */
class DeployProfilesTest {

	private static boolean production(String csv) {
		MockEnvironment env = new MockEnvironment();
		if (!csv.isEmpty()) {
			env.setActiveProfiles(csv.split(","));
		}
		return DeployProfiles.isProduction(env);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "prod", "PROD", "production", "staging", "demo", "prod,demo", "dev,prod", "DEV,prod", " Prod ,dev", "prod,test", "prod,lab", "lab"})
	void treatedAsProduction(String profiles) {
		assertThat(production(profiles)).isTrue();
	}

	@Test
	void onlyDefaultProdIsProduction() {
		MockEnvironment env = new MockEnvironment();
		env.setDefaultProfiles("prod");
		assertThat(DeployProfiles.isProduction(env)).isTrue();
	}

	@Test
	void onlyDefaultDevWithEmptyActiveIsProduction() {
		MockEnvironment env = new MockEnvironment();
		env.setDefaultProfiles("dev");
		assertThat(DeployProfiles.isProduction(env)).isTrue();
	}

	@Test
	void activeUpperDevWithProdIsProduction() {
		MockEnvironment env = new MockEnvironment();
		env.setActiveProfiles("DEV", "prod");
		assertThat(DeployProfiles.isProduction(env)).isTrue();
	}

	@Test
	void activeProdOverridesDefaultDev() {
		MockEnvironment env = new MockEnvironment();
		env.setDefaultProfiles("dev");
		env.setActiveProfiles("staging");
		assertThat(DeployProfiles.isProduction(env)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"dev", "test", "local", "demo,test", "dev,kingbase", "DEV", " Test ", "Local"})
	void treatedAsNonProduction(String profiles) {
		assertThat(production(profiles)).isFalse();
	}
}
