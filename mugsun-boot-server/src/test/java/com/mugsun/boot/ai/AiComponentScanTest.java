package com.mugsun.boot.ai;

import com.mugsun.boot.MugsunApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.ClassUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 安全回归（B1）：AI 包不得被启动类的根扫描注册，只能经 {@link AiAutoConfiguration}（受 mugsun.ai.enabled 控制）装配。
 * <p>只看 Bean 定义，不起 Web 容器、不连库，无需 Docker。端到端 404 见 {@code AiDisabledApiTest}。
 */
class AiComponentScanTest {

	private static final String AI_PKG = "com.mugsun.boot.ai.";

	@Test
	@DisplayName("根扫描排除 gis/track/ai 三个可选模块包，且 AI 类确实在类路径上")
	void rootScanExcludesOptionalModules() {
		assertThat(ClassUtils.isPresent("com.mugsun.boot.ai.controller.AiChatController", null)).isTrue();

		ComponentScan scan = MugsunApplication.class.getAnnotation(ComponentScan.class);
		ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(true);
		Arrays.stream(scan.excludeFilters())
			.flatMap(f -> Arrays.stream(f.pattern()))
			.forEach(p -> provider.addExcludeFilter(new RegexPatternTypeFilter(Pattern.compile(p))));

		List<String> candidates = provider.findCandidateComponents("com.mugsun.boot").stream()
			.map(BeanDefinition::getBeanClassName)
			.toList();

		assertThat(candidates).anyMatch(n -> n.startsWith("com.mugsun.boot.system."));
		assertThat(candidates).noneMatch(n -> n.startsWith(AI_PKG));
		assertThat(candidates).noneMatch(n -> n.startsWith("com.mugsun.boot.gis."));
		assertThat(candidates).noneMatch(n -> n.startsWith("com.mugsun.boot.track."));
	}

	@Test
	@DisplayName("mugsun.ai.enabled=false：AI 自动装配不注册任何 AI Controller")
	void disabledRegistersNoAiControllers() {
		assertThat(aiControllerDefinitions(false)).isEmpty();
	}

	@Test
	@DisplayName("mugsun.ai.enabled=true：AI 自动装配注册 AI Controller（对照组）")
	void enabledRegistersAiControllers() {
		assertThat(aiControllerDefinitions(true))
			.contains("com.mugsun.boot.ai.controller.AiChatController",
				"com.mugsun.boot.ai.controller.AiModelController");
	}

	/** 只解析配置类、登记 Bean 定义，不实例化（AI 的依赖无需就绪） */
	private static Set<String> aiControllerDefinitions(boolean enabled) {
		try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
			ctx.setEnvironment(new MockEnvironment().withProperty("mugsun.ai.enabled", String.valueOf(enabled)));
			ctx.register(AiAutoConfiguration.class);
			ConfigurationClassPostProcessor parser = new ConfigurationClassPostProcessor();
			parser.setEnvironment(ctx.getEnvironment());
			parser.setResourceLoader(ctx);
			parser.setBeanClassLoader(ctx.getClassLoader());
			parser.postProcessBeanDefinitionRegistry(ctx);
			return Arrays.stream(ctx.getBeanDefinitionNames())
				.map(name -> ctx.getBeanDefinition(name).getBeanClassName())
				.filter(cls -> cls != null && cls.startsWith(AI_PKG + "controller."))
				.collect(java.util.stream.Collectors.toSet());
		}
	}
}
