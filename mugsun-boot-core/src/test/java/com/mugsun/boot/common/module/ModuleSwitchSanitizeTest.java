package com.mugsun.boot.common.module;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B96-4：坏值进启动报错、FailureAnalyzer 说明、运行时非法值告警与 1/0 弃用告警之前，所有控制字符都要转义，防日志注入。
 */
class ModuleSwitchSanitizeTest {

	private static final String PROP = "mugsun.demo2.enabled";

	/** 每个值都含至少一个必须转义的字符 */
	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = {
		"true\r\n2026-10-18 INFO fake log line",
		"\ttrue",
		"fa\u0000lse",
		"\u001b[31mred\u001b[0m",
		"x\u007f",
		"x\u0085y",
		"x\u009b31m",
		"a\u2028b\u2029c",
		"evil\u202egnp.exe",
		"zero\u200bwidth",
		"bom\ufeff"})
	@DisplayName("quote 转义 C0 / DEL / C1 / 格式字符 / 行段分隔符，输出里不剩任何控制字符")
	void quoteEscapesAllControls(String raw) {
		String q = ModuleSwitch.quote(raw);
		assertThat(q).startsWith("'").endsWith("'");
		assertNoControls(q);
		assertThat(q).containsPattern("\\\\u[0-9A-F]{4}");
		assertNoControls(ModuleSwitch.strictError(PROP, raw));
		assertNoControls(new InvalidModuleSwitchException(PROP, raw).getMessage());
	}

	@Test
	@DisplayName("具体转义形式：CR LF TAB 写成 \\u000D \\u000A \\u0009；反斜杠写成两个；普通字符与空格原样")
	void escapeForm() {
		assertThat(ModuleSwitch.quote("a\r\n\tb")).isEqualTo("'a\\u000D\\u000A\\u0009b'");
		assertThat(ModuleSwitch.quote("\\u000A")).isEqualTo("'\\\\u000A'");
		assertThat(ModuleSwitch.quote(" true ")).isEqualTo("' true '");
		assertThat(ModuleSwitch.quote("开关")).isEqualTo("'开关'");
		assertThat(ModuleSwitch.quote(null)).isEqualTo("null");
	}

	@Test
	@DisplayName("超长值按码点截断到 64 个并加省略号，不会把代理对劈成半个字符")
	void truncatesByCodePoint() {
		String emoji = "\uD83D\uDE00";
		String q = ModuleSwitch.quote(emoji.repeat(100));
		assertThat(q).isEqualTo("'" + emoji.repeat(ModuleSwitch.QUOTE_MAX) + "…'");
		assertThat(ModuleSwitch.quote("x".repeat(64))).isEqualTo("'" + "x".repeat(64) + "'");
		String withNewlines = "\n".repeat(100);
		assertThat(ModuleSwitch.quote(withNewlines)).isEqualTo("'" + "\\u000A".repeat(64) + "…'");
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@ValueSource(strings = {"yes\r\nWARN forged", "\t1\n", "0\u0085", "yes\u2028"})
	@DisplayName("运行时告警（非法值按关闭 / 1、0 弃用）写进日志的原值不含控制字符")
	void runtimeWarnLogsAreSanitized(String raw) {
		Logger logger = (Logger) LoggerFactory.getLogger(ModuleSwitchRuntime.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			new ModuleSwitchRuntime(System::nanoTime, Duration.ofMinutes(10)).enabled(ModuleSwitch.PARAM_AI, raw);
			assertThat(appender.list).as("应打出一条告警").hasSize(1);
			String line = appender.list.get(0).getFormattedMessage();
			assertNoControls(line);
			assertThat(line).contains(ModuleSwitch.quote(raw));
		} finally {
			logger.detachAppender(appender);
		}
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnModuleSwitch(PROP)
	static class DemoModule {
		@Bean
		String demo2Marker() {
			return "on";
		}
	}

	private static Throwable startupFailure(String raw) {
		AtomicReference<Throwable> out = new AtomicReference<>();
		new ApplicationContextRunner()
			.withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
				.addFirst(new MapPropertySource("t", Map.of(PROP, raw))))
			.withUserConfiguration(DemoModule.class)
			.run(ctx -> out.set(ctx.getStartupFailure()));
		return out.get();
	}

	@Test
	@DisplayName("FailureAnalyzer 已在 spring.factories 注册，并把非法开关值整理成 Description / Action")
	void failureAnalyzerRegisteredAndClean() {
		List<FailureAnalyzer> analyzers = SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
			.load(FailureAnalyzer.class);
		assertThat(analyzers).as("spring.factories 里注册了 ModuleSwitchFailureAnalyzer")
			.anyMatch(ModuleSwitchFailureAnalyzer.class::isInstance);

		Throwable failure = startupFailure("1\r\nERROR forged line");
		assertThat(failure).as("非法值应启动失败").isNotNull();
		FailureAnalysis analysis = analyzers.stream().filter(ModuleSwitchFailureAnalyzer.class::isInstance)
			.map(a -> a.analyze(failure)).filter(Objects::nonNull).findFirst().orElseThrow();

		assertThat(analysis.getDescription())
			.contains(PROP, "MUGSUN_DEMO2_ENABLED", "'1\\u000D\\u000AERROR forged line'", "只认 true 或 false");
		assertThat(analysis.getAction()).contains("改成 true 或 false", "写 false", "删掉这一项，按默认开启");
		assertNoControls(analysis.getDescription());
		assertNoControls(analysis.getAction());
		assertThat(analysis.getCause()).isInstanceOf(InvalidModuleSwitchException.class);
	}

	@Test
	@DisplayName("不是模块开关引起的失败，分析器不接管")
	void analyzerIgnoresOtherFailures() {
		assertThat(new ModuleSwitchFailureAnalyzer().analyze(new IllegalStateException("other"))).isNull();
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
