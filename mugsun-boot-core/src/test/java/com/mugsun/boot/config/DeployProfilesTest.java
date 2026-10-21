package com.mugsun.boot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只有严格小写的 dev/test/local 白名单才算非生产；没配、staging、大写、同时带 prod 一律按生产。
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
	@ValueSource(strings = {"", "prod", "PROD", "production", "staging", "Dev", "TEST", "demo", "prod,demo", "dev,prod", "prod,test", "prod,lab", "lab"})
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
	void onlyDefaultDevIsNonProduction() {
		MockEnvironment env = new MockEnvironment();
		env.setDefaultProfiles("dev");
		assertThat(DeployProfiles.isProduction(env)).isFalse();
	}

	@Test
	void activeProdOverridesDefaultDev() {
		MockEnvironment env = new MockEnvironment();
		env.setDefaultProfiles("dev");
		env.setActiveProfiles("staging");
		assertThat(DeployProfiles.isProduction(env)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = {"dev", "test", "local", "demo,test", "dev,kingbase"})
	void treatedAsNonProduction(String profiles) {
		assertThat(production(profiles)).isFalse();
	}
}
