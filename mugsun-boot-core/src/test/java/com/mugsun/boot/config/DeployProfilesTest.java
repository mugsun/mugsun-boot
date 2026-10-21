package com.mugsun.boot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

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
	@ValueSource(strings = {"", "prod", "PROD", "production", "staging", "demo", "prod,demo", "dev,prod", "DEV,prod", " Prod ,dev", "production,dev", "staging,dev", "prd,test", "local, STAGING", "prod,test", "prod,lab", "lab"})
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

	/** 只通过环境变量设置 active（与运行时一样走 systemEnvironment 源），判定读的是 getActiveProfiles() */
	private static StandardEnvironment envVar(String value) {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
			new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
				Map.<String, Object>of("SPRING_PROFILES_ACTIVE", value)));
		env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		return env;
	}

	@Test
	void activeProdFromEnvironmentVariableIsProduction() {
		StandardEnvironment env = envVar("prod");
		assertThat(env.getActiveProfiles()).containsExactly("prod");
		assertThat(DeployProfiles.isProduction(env)).isTrue();
	}

	@Test
	void activeDevFromEnvironmentVariableIsNonProduction() {
		assertThat(DeployProfiles.isProduction(envVar("dev"))).isFalse();
	}
}
