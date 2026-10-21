package com.mugsun.boot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.OrderUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 非 dev/test/local 下 seed-fronttest=true 拒绝启动；白名单 profile 或开关关闭放行。
 */
class LabSeedProdGuardTest {

	private static LabSeedProdGuard guard(String profiles, String value) {
		MockEnvironment env = new MockEnvironment();
		if (!profiles.isEmpty()) {
			env.setActiveProfiles(profiles.split(","));
		}
		if (value != null) {
			env.setProperty(LabSeedProdGuard.KEY, value);
		}
		return new LabSeedProdGuard(env);
	}

	@ParameterizedTest
	@CsvSource(value = {"'',true", "staging,true", "'dev,prod',true", "prod,true", "PROD,true", "production,true", "'prod,lab',true"})
	void refusesOutsideDevWhenSeedOn(String profiles, String value) {
		assertThatThrownBy(() -> guard(profiles, value).run())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining(LabSeedProdGuard.KEY)
			.hasMessageNotContaining("123456");
	}

	@ParameterizedTest
	@CsvSource(value = {"dev,true", "test,true", "local,true", "'',false", "prod,false"})
	void startsInDevOrWhenSeedOff(String profiles, String value) {
		assertThatCode(() -> guard(profiles, value).run()).doesNotThrowAnyException();
	}

	@Test
	void startsWhenKeyMissing() {
		assertThatCode(() -> guard("", null).run()).doesNotThrowAnyException();
	}

	@ParameterizedTest
	@ValueSource(strings = {"TRUE", "True", " true ", "true "})
	void refusesSpellingVariants(String value) {
		assertThatThrownBy(() -> guard("", value).run()).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void refusesWhenSetViaEnvironmentVariable() {
		StandardEnvironment env = new StandardEnvironment();
		// 与运行时一样用 systemEnvironment 这个名字：Boot 的宽松绑定只对它启用环境变量映射
		env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
			new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
			Map.<String, Object>of("MUGSUN_LAB_SEEDFRONTTEST", "true")));
		// 与 Spring Boot 运行时一致：挂上宽松绑定，MUGSUN_LAB_SEEDFRONTTEST 才能映射到点分键
		ConfigurationPropertySources.attach(env);
		assertThatThrownBy(() -> new LabSeedProdGuard(env).run()).isInstanceOf(IllegalStateException.class);
		StandardEnvironment env3 = new StandardEnvironment();
		env3.getPropertySources().addFirst(new MapPropertySource("x", Map.of()));
		assertThatCode(() -> new LabSeedProdGuard(env3).run()).doesNotThrowAnyException();
	}

	@Test
	void guardOrderedBeforeDataInitializer() {
		int guardOrder = OrderUtils.getOrder(LabSeedProdGuard.class, Ordered.LOWEST_PRECEDENCE);
		int seedOrder = OrderUtils.getOrder(DataInitializer.class, Ordered.LOWEST_PRECEDENCE);
		assertThat(guardOrder).isLessThan(seedOrder);
	}

	@org.junit.jupiter.api.Test
	void refusesWhenOnlyDefaultProfileIsProd() {
		MockEnvironment env = new MockEnvironment();
		env.setDefaultProfiles("prod");
		env.setProperty(LabSeedProdGuard.KEY, "true");
		assertThatThrownBy(() -> new LabSeedProdGuard(env).run()).isInstanceOf(IllegalStateException.class);
	}

	@org.junit.jupiter.api.Test
	void startsWhenOnlyDefaultProfileIsDev() {
		MockEnvironment env = new MockEnvironment();
		env.setDefaultProfiles("dev");
		env.setProperty(LabSeedProdGuard.KEY, "true");
		assertThatCode(() -> new LabSeedProdGuard(env).run()).doesNotThrowAnyException();
	}
}
