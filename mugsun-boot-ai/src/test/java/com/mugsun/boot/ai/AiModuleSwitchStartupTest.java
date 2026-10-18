package com.mugsun.boot.ai;

import com.mugsun.boot.common.module.InvalidModuleSwitchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B96-3：用真实的 {@link AiAutoConfiguration} 与模块自带的 mugsun-module-ai.yml 启动，非法开关值必须启动失败并给出改法；
 * {@code false} 正常启动且不装配模块。只验证装配条件这一步，所以不需要数据库。
 */
class AiModuleSwitchStartupTest {

	private static final String PROP = "mugsun.ai.enabled";
	private static final String ENV = "MUGSUN_AI_ENABLED";

	private static List<PropertySource<?>> moduleYml() throws IOException {
		return new YamlPropertySourceLoader().load("mugsun-module-ai", new ClassPathResource("mugsun-module-ai.yml"));
	}

	/** env 为 null 表示不设环境变量；extraYml 为 null 表示部署方没写自己的 yml */
	private static ApplicationContextRunner runner(String env, String extraYml) {
		return new ApplicationContextRunner()
			.withInitializer(ctx -> {
				var sources = ctx.getEnvironment().getPropertySources();
				try {
					moduleYml().forEach(sources::addLast);
					if (extraYml != null) {
						new YamlPropertySourceLoader().load("application-local",
							new ByteArrayResource(extraYml.getBytes(StandardCharsets.UTF_8))).forEach(sources::addFirst);
					}
				} catch (IOException e) {
					throw new IllegalStateException(e);
				}
				if (env != null) {
					sources.addFirst(new SystemEnvironmentPropertySource(
						StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(ENV, env)));
				}
			})
			.withConfiguration(AutoConfigurations.of(AiAutoConfiguration.class));
	}

	@ParameterizedTest(name = "[{index}] AI=''{0}''")
	@ValueSource(strings = {"", "1", "0", " true", "false ", "yes", "off", "1\r\nforged"})
	@DisplayName("环境变量 AI 设成空值、1/0、带空格或其它值：真实模块启动失败，报错写出变量名与改法")
	void illegalEnvFailsStartup(String value) {
		runner(value, null).run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(ctx.getStartupFailure()).rootCause()
				.isInstanceOf(InvalidModuleSwitchException.class)
				.hasMessageContaining(PROP)
				.hasMessageContaining(ENV)
				.hasMessageContaining("请改成 true 或 false")
				.hasMessageContaining("删掉这个变量，按默认开启")
				.hasMessageNotContaining("\r")
				.hasMessageNotContaining("\n");
		});
	}

	@Test
	@DisplayName("部署方 yml 里写 enabled: 留空，同样启动失败")
	void emptyYmlFailsStartup() {
		runner(null, "mugsun:\n  ai:\n    enabled:\n").run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(ctx.getStartupFailure()).rootCause()
				.isInstanceOf(InvalidModuleSwitchException.class)
				.hasMessageContaining(PROP)
				.hasMessageContaining("''");
		});
	}

	@ParameterizedTest(name = "[{index}] AI=''{0}''")
	@ValueSource(strings = {"false", "FALSE", "False"})
	@DisplayName("AI=false（不区分大小写）：正常启动，模块不装配")
	void falseSkipsModule(String value) {
		runner(value, null).run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx).doesNotHaveBean(AiAutoConfiguration.class);
			assertThat(ctx).doesNotHaveBean(AiModuleService.class);
		});
	}

	@Test
	@DisplayName("模块 yml 的默认值是 true：环境变量不设时按开启（只看条件报告，不真正装配整个模块）")
	void unsetDefaultsToOn() throws IOException {
		String declared = moduleYml().stream().map(ps -> ps.getProperty(PROP)).filter(java.util.Objects::nonNull)
			.map(Object::toString).findFirst().orElseThrow();
		assertThat(declared).isEqualTo("${" + ENV + ":true}");
		assertThat(com.mugsun.boot.common.module.ModuleSwitch.parseStrict(null).enabled()).isTrue();
		assertThat(com.mugsun.boot.common.module.ModuleSwitch.parseStrict("true").enabled()).isTrue();
	}
}
