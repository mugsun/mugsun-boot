package com.mugsun.boot.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B88-1 / B88-2：用真实的自动装配导入流程（Spring AI starter 全在 classpath、MCP Server 开关打开）启动最小应用，
 * 数一数容器里 Spring AI / MCP SDK 的 Bean。
 * <p>判定 fail closed：类型解析不出来的 Bean 一律计入「可疑」，不当作「不是 Spring AI」放过；
 * 另有 {@code true} 对照组，证明同一套计数在 AI 开启时确实能数到 Spring AI 的 Bean。
 */
class AiSwitchSpringAiContextTest {

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class MinimalApp {
	}

	private static ConfigurableApplicationContext start(String aiEnabled) {
		return start(aiEnabled, "");
	}

	/** {@code extraExclude}：对照组排除本项目的 AiAutoConfiguration（它要扫 Service/Mapper，最小应用里没有数据库） */
	private static ConfigurableApplicationContext start(String aiEnabled, String extraExclude) {
		// 用命令行参数（优先级最高），并且不读应用自带的 application.yml：
		// 否则 yml 里的 mugsun.ai.enabled=${MUGSUN_AI_ENABLED:true} 会盖过这里的取值，也会拉起数据源、Flyway 等
		return new SpringApplicationBuilder(MinimalApp.class)
			.web(WebApplicationType.NONE)
			.run(
				"--spring.config.location=optional:classpath:/ai-switch-context-test/",
				"--mugsun.ai.enabled=" + aiEnabled,
				"--spring.ai.mcp.server.enabled=true",
				"--spring.ai.mcp.client.enabled=false",
				"--spring.ai.openai.api-key=sk-test",
				"--spring.autoconfigure.exclude="
					+ "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
					+ "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration,"
					+ thirdPartyAutoConfigurations()
					+ extraExclude,
				"--spring.main.banner-mode=off");
	}

	/**
	 * 第三方（MyBatis-Flex、warm-flow、Sa-Token、JetCache 等）的自动装配：它们要数据库、Redis，与本测试无关，统一排除。
	 * 只保留 Spring Boot、Spring AI 自身以及本项目 AI 模块的自动装配，过滤器面对的仍是真实的 Spring AI 候选列表。
	 */
	private static String thirdPartyAutoConfigurations() {
		List<String> excluded = new ArrayList<>();
		for (String name : ImportCandidates.load(AutoConfiguration.class, AiSwitchSpringAiContextTest.class.getClassLoader())) {
			if (!name.startsWith("org.springframework.") && !name.equals(AiAutoConfiguration.class.getName())) {
				excluded.add(name);
			}
		}
		return String.join(",", excluded);
	}

	/** Spring AI / MCP SDK 的 Bean，以及类型解析不出来的 Bean（fail closed） */
	private static List<String> springAiOrUnknownBeans(ConfigurableApplicationContext context) {
		ConfigurableListableBeanFactory factory = context.getBeanFactory();
		List<String> found = new ArrayList<>();
		for (String name : factory.getBeanDefinitionNames()) {
			BeanDefinition definition = factory.getBeanDefinition(name);
			Class<?> type = factory.getType(name);
			String typeName = type != null ? type.getName() : null;
			String className = definition.getBeanClassName();
			if (typeName == null) {
				found.add(name + " : <unresolved>");
			} else if (isSpringAi(typeName) || isSpringAi(name) || (className != null && isSpringAi(className))) {
				found.add(name + " : " + typeName);
			}
		}
		return found;
	}

	private static boolean isSpringAi(String name) {
		return name.startsWith("org.springframework.ai.") || name.startsWith("io.modelcontextprotocol.");
	}

	@Test
	@DisplayName("前提：命令行取值确实生效（不被应用 yml 覆盖）")
	void valueReachesEnvironment() {
		try (ConfigurableApplicationContext context = start("1")) {
			org.assertj.core.api.Assertions.assertThat(context.getEnvironment().getProperty("mugsun.ai.enabled")).isEqualTo("1");
		}
	}

	@Test
	@DisplayName("对照：mugsun.ai.enabled=true 时能数到 Spring AI 的 Bean（计数本身有效）")
	void enabledHasSpringAiBeans() {
		try (ConfigurableApplicationContext context = start("true", "," + AiAutoConfiguration.class.getName())) {
			assertThat(springAiOrUnknownBeans(context)).isNotEmpty()
				.noneMatch(s -> s.endsWith("<unresolved>"));
		}
	}

	@Test
	@DisplayName("mugsun.ai.enabled=1：算关闭，Spring AI 的 Bean 为 0")
	void valueOneHasNoSpringAiBeans() {
		try (ConfigurableApplicationContext context = start("1")) {
			assertThat(springAiOrUnknownBeans(context)).isEmpty();
			assertThat(context.getBeanNamesForType(AiAutoConfiguration.class)).isEmpty();
		}
	}

	@Test
	@DisplayName("mugsun.ai.enabled=flase（拼写错误）：应用照常启动，AI 按关闭处理")
	void typoStartsWithAiOff() {
		try (ConfigurableApplicationContext context = start("flase")) {
			assertThat(context.isActive()).isTrue();
			assertThat(springAiOrUnknownBeans(context)).isEmpty();
			assertThat(context.getBeanNamesForType(AiAutoConfiguration.class)).isEmpty();
		}
	}
}
