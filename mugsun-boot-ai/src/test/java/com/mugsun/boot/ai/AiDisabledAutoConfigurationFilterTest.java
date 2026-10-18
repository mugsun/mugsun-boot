package com.mugsun.boot.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
