package com.mugsun.boot.captcha;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台默认题型三处一致：CaptchaProperties 代码默认、application.yml、application-demo.yml 都是 SLIDER,WORD_CLICK
 * （多题型 = 每次随机出一种，config 返回 RANDOM）；ROTATE / ICON_CLICK 不在默认里。
 */
class CaptchaDefaultTypesTest {

	private static final List<String> EXPECTED = List.of("SLIDER", "WORD_CLICK");

	@Test
	void codeDefault() {
		assertThat(CaptchaProperties.PLATFORM_DEFAULT_TYPES).containsExactlyElementsOf(EXPECTED);
		CaptchaProperties p = new CaptchaProperties();
		assertThat(p.getDefaultTypes()).containsExactlyElementsOf(EXPECTED);
		assertThat(p.validate()).isEqualTo(EnumSet.of(CaptchaType.SLIDER, CaptchaType.WORD_CLICK))
			.doesNotContain(CaptchaType.ROTATE, CaptchaType.ICON_CLICK).hasSizeGreaterThan(1);
	}

	@Test
	void applicationYmlDefault() throws Exception {
		assertThat(yamlTypes("application.yml")).containsExactlyElementsOf(EXPECTED);
	}

	@Test
	void demoYmlDefault() throws Exception {
		assertThat(yamlTypes("application-demo.yml")).containsExactlyElementsOf(EXPECTED);
	}

	private static List<String> yamlTypes(String file) throws Exception {
		StandardEnvironment env = new StandardEnvironment();
		for (PropertySource<?> ps : new YamlPropertySourceLoader().load(file, new ClassPathResource(file))) {
			env.getPropertySources().addLast(ps);
		}
		return Binder.get(env).bind("mugsun.captcha.default-types", Bindable.listOf(String.class))
			.orElseThrow(() -> new AssertionError(file + " 缺少 mugsun.captcha.default-types"));
	}
}
