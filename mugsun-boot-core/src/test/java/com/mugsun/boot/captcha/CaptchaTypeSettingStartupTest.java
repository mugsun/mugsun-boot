package com.mugsun.boot.captcha;

import com.mugsun.boot.common.setting.InvalidSettingException;
import com.mugsun.boot.common.setting.InvalidSettingFailureAnalyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v0.6.3：验证码题型配置（{@code mugsun.captcha.default-types}、废弃键 {@code mugsun.captcha.type}）严格解析。
 * 非法值（含已移除的 IMAGE）启动失败并写明改法；只绑定 {@link CaptchaProperties}，不需要数据库。
 */
class CaptchaTypeSettingStartupTest {

	// 不加 @Configuration：否则会被集成测试的组件扫描捡到，与 @Component 的 CaptchaProperties 重复成两个 bean
	@EnableConfigurationProperties(CaptchaProperties.class)
	static class Cfg {
	}

	private static ApplicationContextRunner env(Map<String, Object> envVars) {
		return new ApplicationContextRunner()
			.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource(
				StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, envVars)))
			.withUserConfiguration(Cfg.class);
	}

	private static ApplicationContextRunner props(Map<String, Object> props) {
		return new ApplicationContextRunner()
			.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("t", props)))
			.withUserConfiguration(Cfg.class);
	}

	@Test
	@DisplayName("不写：平台默认五种全开")
	void unsetUsesPlatformDefault() {
		env(Map.of()).run(ctx -> {
			assertThat(ctx).hasNotFailed();
			CaptchaProperties p = ctx.getBean(CaptchaProperties.class);
			assertThat(p.validate()).containsExactlyInAnyOrder(CaptchaType.values());
		});
	}

	@ParameterizedTest(name = "[{index}] DEFAULT_TYPES=''{0}''")
	@ValueSource(strings = {"SLIDER", "slider,word_click", "SLIDER,ROTATE,WORD_CLICK,ICON_CLICK,JIGSAW", "JIGSAW"})
	@DisplayName("合法题型（不区分大小写）：正常启动")
	void legalTypesStart(String v) {
		env(Map.of("MUGSUN_CAPTCHA_DEFAULT_TYPES", v)).run(ctx -> assertThat(ctx).hasNotFailed());
	}

	@ParameterizedTest(name = "[{index}] DEFAULT_TYPES=''{0}''")
	@ValueSource(strings = {"IMAGE", "image", "SLIDER,IMAGE"})
	@DisplayName("default-types 含 IMAGE：启动失败，写明 IMAGE 已移除与改法")
	void imageInDefaultTypesFails(String v) {
		env(Map.of("MUGSUN_CAPTCHA_DEFAULT_TYPES", v)).run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(ctx.getStartupFailure()).rootCause()
				.isInstanceOf(InvalidSettingException.class)
				.hasMessageContaining("mugsun.captcha.default-types")
				.hasMessageContaining("MUGSUN_CAPTCHA_DEFAULT_TYPES")
				.hasMessageContaining("IMAGE 已移除")
				.hasMessageContaining("mugsun.captcha.enabled=false");
		});
	}

	@ParameterizedTest(name = "[{index}] DEFAULT_TYPES=''{0}''")
	@ValueSource(strings = {"FOO", "SLIDER,FOO", "SLIDER,,WORD_CLICK", "TRUE", "SLID\u0000ER", "WORD\u202ECLICK"})
	@DisplayName("default-types 含未知值或空元素：启动失败，原值转义控制字符")
	void unknownTypeFails(String v) {
		env(Map.of("MUGSUN_CAPTCHA_DEFAULT_TYPES", v)).run(ctx -> {
			assertThat(ctx).hasFailed();
			Throwable root = ctx.getStartupFailure();
			while (root.getCause() != null) {
				root = root.getCause();
			}
			assertThat(root).isInstanceOf(InvalidSettingException.class)
				.hasMessageContaining("mugsun.captcha.default-types")
				.hasMessageContaining("可选值：SLIDER、ROTATE、WORD_CLICK、ICON_CLICK、JIGSAW");
			assertNoControls(root.getMessage());
		});
	}

	@Test
	@DisplayName("default-types 设成空列表：启动失败")
	void emptyListFails() {
		props(Map.of("mugsun.captcha.default-types", List.of())).run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(ctx.getStartupFailure()).rootCause().isInstanceOf(InvalidSettingException.class)
				.hasMessageContaining("不能为空");
		});
	}

	@ParameterizedTest(name = "[{index}] TYPE=''{0}''")
	@ValueSource(strings = {"IMAGE", "image"})
	@DisplayName("废弃键 type=IMAGE：启动失败，写明 IMAGE 已移除")
	void legacyTypeImageFails(String v) {
		env(Map.of("MUGSUN_CAPTCHA_TYPE", v)).run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(ctx.getStartupFailure()).rootCause().isInstanceOf(InvalidSettingException.class)
				.hasMessageContaining("mugsun.captcha.type")
				.hasMessageContaining("MUGSUN_CAPTCHA_TYPE")
				.hasMessageContaining("IMAGE 已移除");
		});
	}

	@ParameterizedTest(name = "[{index}] TYPE=''{0}''")
	@ValueSource(strings = {"", " SLIDER", "ROTATE", "RANDOM", "1\r\nERROR forged"})
	@DisplayName("废弃键 type 的其它值（空值、带空格、新题型名）：启动失败，原值转义")
	void legacyTypeOtherFails(String v) {
		env(Map.of("MUGSUN_CAPTCHA_TYPE", v)).run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(ctx.getStartupFailure()).rootCause().isInstanceOf(InvalidSettingException.class)
				.hasMessageContaining("mugsun.captcha.type")
				.hasMessageNotContaining("\r").hasMessageNotContaining("\n");
		});
	}

	@ParameterizedTest(name = "[{index}] TYPE=''{0}''")
	@ValueSource(strings = {"SLIDER", "slider"})
	@DisplayName("废弃键 type=SLIDER：兼容老配置，正常启动（只打 WARN，不起作用）")
	void legacyTypeSliderStarts(String v) {
		env(Map.of("MUGSUN_CAPTCHA_TYPE", v)).run(ctx -> assertThat(ctx).hasNotFailed());
	}

	@Test
	@DisplayName("总开关 enabled=false 与合法题型同时存在：正常启动")
	void masterSwitchOffStarts() {
		env(Map.of("MUGSUN_CAPTCHA_ENABLED", "false")).run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx.getBean(CaptchaProperties.class).isEnabled()).isFalse();
		});
	}

	@Test
	@SuppressWarnings("deprecation")
	@DisplayName("FailureAnalyzer 已在 spring.factories 注册，Description / Action 不含控制字符")
	void failureAnalyzerRegisteredAndClean() {
		List<String> names = SpringFactoriesLoader.loadFactoryNames(FailureAnalyzer.class, getClass().getClassLoader());
		assertThat(names).contains(InvalidSettingFailureAnalyzer.class.getName());
		AtomicReference<Throwable> failure = new AtomicReference<>();
		env(Map.of("MUGSUN_CAPTCHA_TYPE", "IMAGE\r\nERROR forged")).run(ctx -> failure.set(ctx.getStartupFailure()));
		FailureAnalysis a = new InvalidSettingFailureAnalyzer().analyze(failure.get());
		assertThat(a).isNotNull();
		assertThat(a.getDescription()).contains("mugsun.captcha.type", "MUGSUN_CAPTCHA_TYPE", "'IMAGE\\u000D\\u000AERROR forged'");
		// 文案本身也固定：配置项、环境变量、转义后的原值、原因，以及「改 / 删掉这一项」的改法
		AtomicReference<Throwable> imageOnly = new AtomicReference<>();
		env(Map.of("MUGSUN_CAPTCHA_TYPE", "IMAGE")).run(ctx -> imageOnly.set(ctx.getStartupFailure()));
		FailureAnalysis img = new InvalidSettingFailureAnalyzer().analyze(imageOnly.get());
		assertThat(img.getDescription()).isEqualTo("配置项 mugsun.captcha.type（环境变量 MUGSUN_CAPTCHA_TYPE）的值 'IMAGE' 不合法："
			+ "取值 IMAGE 已移除。" + CaptchaProperties.REMOVED_TYPES.get("IMAGE"));
		assertThat(img.getAction()).isEqualTo("改 MUGSUN_CAPTCHA_TYPE（或 yml 里的 mugsun.captcha.type）后再启动；也可以删掉这一项，按默认值启动。");
		assertThat(a.getAction()).contains("MUGSUN_CAPTCHA_TYPE", "删掉这一项");
		assertNoControls(a.getDescription());
		assertNoControls(a.getAction());
		assertThat(new InvalidSettingFailureAnalyzer().analyze(new IllegalStateException("other"))).isNull();
	}

	@Test
	@DisplayName("解析器本身：重复值合并、null 表示未设置")
	void parserBasics() {
		assertThat(com.mugsun.boot.common.setting.StrictEnumSetting.parseList("p", List.of("SLIDER", "slider"),
			CaptchaType.class, CaptchaProperties.REMOVED_TYPES)).isEqualTo(EnumSet.of(CaptchaType.SLIDER));
		assertThat(com.mugsun.boot.common.setting.StrictEnumSetting.parseList("p", null, CaptchaType.class, null)).isNull();
		assertThat(com.mugsun.boot.common.setting.StrictEnumSetting.parseOne("p", null, CaptchaType.class, null)).isNull();
	}

	static void assertNoControls(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			int type = Character.getType(c);
			assertThat(Character.isISOControl(c) || type == Character.FORMAT
				|| type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR)
				.as("位置 %d 有控制字符 U+%04X：%s", i, (int) c, s).isFalse();
		}
	}
}
