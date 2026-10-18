package com.mugsun.boot.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurationImportFilter;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class AiDisabledAutoConfigurationFilterTest {

	private static final String[] CANDIDATES = {
		"org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration",
		"org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
		"org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration",
		"org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration",
		"com.mugsun.boot.track.TrackAutoConfiguration",
		"org.springframework.aix.Fake",
		null
	};

	private static boolean[] match(MockEnvironment env) {
		AiDisabledAutoConfigurationFilter filter = new AiDisabledAutoConfigurationFilter();
		filter.setEnvironment(env);
		return filter.match(CANDIDATES, null);
	}

	@Test
	@DisplayName("mugsun.ai.enabled=false：剔除 org.springframework.ai.* 全部自动装配，其余不动")
	void disabledFiltersSpringAi() {
		assertThat(match(new MockEnvironment().withProperty("mugsun.ai.enabled", "false")))
			.containsExactly(false, false, false, true, true, true, true);
	}

	@Test
	@DisplayName("缺省与 true：不过滤")
	void enabledKeepsAll() {
		assertThat(match(new MockEnvironment())).containsOnly(true);
		assertThat(match(new MockEnvironment().withProperty("mugsun.ai.enabled", "true"))).containsOnly(true);
	}

	@ParameterizedTest(name = "mugsun.ai.enabled={0}：与 AiAutoConfiguration 一样算关闭，剔除 Spring AI")
	@ValueSource(strings = {"1", "on", "yes", "flase", "False", "", " true", "0", "off"})
	@DisplayName("只有不区分大小写的 true 才算开启（B88-1）")
	void nonTrueValuesFilterSpringAi(String value) {
		assertThat(match(new MockEnvironment().withProperty("mugsun.ai.enabled", value)))
			.containsExactly(false, false, false, true, true, true, true);
	}

	@ParameterizedTest(name = "mugsun.ai.enabled={0}：开启，不过滤")
	@ValueSource(strings = {"true", "TRUE", "True"})
	void trueIgnoringCaseKeepsAll(String value) {
		assertThat(match(new MockEnvironment().withProperty("mugsun.ai.enabled", value))).containsOnly(true);
	}

	@Test
	@DisplayName("读不到配置（Environment 未注入）时按关闭处理，不默认放行（B88-2）")
	void missingEnvironmentFailsClosed() {
		AiDisabledAutoConfigurationFilter filter = new AiDisabledAutoConfigurationFilter();
		assertThat(filter.match(CANDIDATES, null)).containsExactly(false, false, false, true, true, true, true);
	}

	@Test
	@DisplayName("被测候选里的 Spring AI 自动装配类确实在 classpath 上（否则剔除断言没有意义）")
	void candidatesArePresent() throws ClassNotFoundException {
		for (int i = 0; i < 3; i++) {
			Class.forName(CANDIDATES[i]);
		}
	}

	@Test
	@DisplayName("环境变量写法 MUGSUN_AI_ENABLED 经 yml 占位符解析后同样生效")
	void placeholderResolved() {
		MockEnvironment env = new MockEnvironment()
			.withProperty("MUGSUN_AI_ENABLED", "false")
			.withProperty("mugsun.ai.enabled", "${MUGSUN_AI_ENABLED:true}");
		assertThat(match(env)[0]).isFalse();
	}

	@Test
	@DisplayName("已在 spring.factories 注册，Spring Boot 导入自动装配时会调用")
	void registeredInSpringFactories() {
		assertThat(SpringFactoriesLoader.loadFactories(AutoConfigurationImportFilter.class, getClass().getClassLoader()))
			.anyMatch(AiDisabledAutoConfigurationFilter.class::isInstance);
	}
}
