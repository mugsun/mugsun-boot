package com.mugsun.boot.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** allowed-paths 启动校验：不合规条目让容器启动失败 */
class MultipartGuardPropertiesTest {

	/** 不加 @Configuration：否则会被集成测试的组件扫描捡到，多注册一个同类型 Bean */
	@EnableConfigurationProperties(MultipartGuardProperties.class)
	static class Config {
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

	@ParameterizedTest(name = "{0}: [{1}]")
	@CsvSource(delimiter = '|', value = {
		"单星通配     | /system/*/upload        | 通配符",
		"双星通配     | /system/**              | 通配符",
		"末尾单星     | /system/file/*          | 通配符",
		"不以 / 开头  | system/file/upload      | 必须以 / 开头",
		"相对上级     | /system/../file/upload  | ..",
		"末尾 ..      | /system/file/..         | ..",
		"分号参数     | /system/file/upload;x=1 | ;",
		"路径变量     | /system/{id}/upload     | 路径变量",
		"只有右括号   | /system/id}/upload      | 路径变量",
	})
	@DisplayName("不合规路径拒绝启动")
	void invalidPathFailsStartup(String name, String path, String reason) {
		runner.withPropertyValues("mugsun.security.multipart.allowed-paths[0]=/system/file/upload",
				"mugsun.security.multipart.allowed-paths[1]=" + path)
			.run(ctx -> {
				assertThat(ctx).as(name).hasFailed();
				assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("allowed-paths[1]")
					.hasMessageContaining(reason);
			});
	}

	@Test
	@DisplayName("空串拒绝启动")
	void emptyFailsStartup() {
		runner.withPropertyValues("mugsun.security.multipart.allowed-paths[0]=")
			.run(ctx -> {
				assertThat(ctx).hasFailed();
				assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("不能为空");
			});
	}

	@Test
	@DisplayName("仅空白拒绝启动")
	void blankFailsStartup() {
		assertThat(MultipartGuardProperties.invalidReason("   ")).isEqualTo("不能为空");
		assertThat(MultipartGuardProperties.invalidReason(null)).isEqualTo("不能为空");
		// 属性源会去掉首尾空白，这里直接校验方法本身
		assertThat(MultipartGuardProperties.invalidReason(" /system/file/upload")).contains("空白");
	}

	@Test
	@DisplayName("默认 5 条和合规的自定义路径正常启动")
	void validPathsStart() {
		runner.run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx.getBean(MultipartGuardProperties.class).getAllowedPaths())
				.containsExactlyElementsOf(MultipartGuardProperties.DEFAULT_ALLOWED_PATHS);
		});
		runner.withPropertyValues("mugsun.security.multipart.allowed-paths[0]=/system/file/upload",
				"mugsun.security.multipart.allowed-paths[1]=/system/demo/upload-v2")
			.run(ctx -> assertThat(ctx).hasNotFailed());
	}
}
