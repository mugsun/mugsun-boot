package com.mugsun.boot.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.boot.test.context.TestConfiguration;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护：src/test 下 com.mugsun 包里不许有会被 MugsunApplication 组件扫描捡到的类
 * （@Configuration / @Component 系列 / @TestConfiguration）。
 * MugsunApplication 的 @ComponentScan 覆盖 com.mugsun.boot 且没有 TypeExcludeFilter，
 * 这类测试类会混进每个集成测试的真实上下文（LabSeedGuardStartupTest$Cfg 曾因此弄坏管理员登录）。
 * 测试配置用 lite 模式（不加注解，靠 withUserConfiguration / SpringApplicationBuilder(source) 显式传入）。
 */
class TestComponentScanGuardTest {

	/** 故意被扫描的测试类：G90 监控探针 */
	private static final Set<String> ALLOWED = Set.of("com.mugsun.boot.monitor.MonitorTestController");

	@Test
	@DisplayName("src/test 里没有会被组件扫描收进应用上下文的类")
	void noScannableTestComponents() throws Exception {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(true) {
			@Override
			protected boolean isCandidateComponent(org.springframework.beans.factory.annotation.AnnotatedBeanDefinition d) {
				// 嵌套静态类也要算进来
				return d.getMetadata().isIndependent();
			}
		};
		scanner.addIncludeFilter(new AnnotationTypeFilter(TestConfiguration.class));
		String testClasses = TestComponentScanGuardTest.class.getProtectionDomain().getCodeSource().getLocation().toString();
		List<String> offenders = scanner.findCandidateComponents("com.mugsun").stream()
			.map(BeanDefinition::getBeanClassName)
			.filter(n -> !ALLOWED.contains(n))
			.filter(n -> fromTestClasses(n, testClasses))
			.sorted()
			.toList();
		assertThat(offenders).as("这些测试类会被组件扫描收进集成测试上下文，去掉 @Configuration/@Component 等注解").isEmpty();
	}

	private static boolean fromTestClasses(String className, String testClasses) {
		var url = TestComponentScanGuardTest.class.getClassLoader().getResource(className.replace('.', '/') + ".class");
		return url != null && url.toString().startsWith(testClasses);
	}
}
