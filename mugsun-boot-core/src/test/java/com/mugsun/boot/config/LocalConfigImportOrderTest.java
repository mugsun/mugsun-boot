package com.mugsun.boot.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.yaml.snakeyaml.Yaml;

/**
 * UX-012 回归：config/application-local.yml 必须能覆盖模块自带配置（mugsun-module-*.yml）。
 * 旧顺序把 local 放在模块之前，spring.config.import 后者覆盖前者，local 里改的 track 数据源 URL 被
 * mugsun-module-track.yml 的默认值静默盖掉——埋点读到另一套库的历史错误，「首次发生」早于新库创建时间。
 */
class LocalConfigImportOrderTest {

	private static final String LOCAL = "optional:file:./config/application-local.yml";

	@SuppressWarnings("unchecked")
	private static List<String> imports() throws Exception {
		try (InputStream in = LocalConfigImportOrderTest.class.getResourceAsStream("/application.yml")) {
			Map<String, Object> root = new Yaml().load(in);
			Map<String, Object> spring = (Map<String, Object>) root.get("spring");
			Map<String, Object> config = (Map<String, Object>) spring.get("config");
			return (List<String>) config.get("import");
		}
	}

	@Test
	void localOverrideIsImportedLast() throws Exception {
		List<String> imports = imports();
		assertThat(imports).contains(LOCAL);
		assertThat(imports.get(imports.size() - 1)).as("local 覆盖须排在所有模块配置之后").isEqualTo(LOCAL);
	}

	/** 用 application.yml 的真实 import 顺序验证 Spring 语义：local 中的 track URL 生效 */
	@Test
	void localTrackUrlWinsOverModuleDefault(@TempDir Path dir) throws Exception {
		Files.writeString(dir.resolve("module.yml"),
			"mybatis-flex.datasource.track.url: jdbc:postgresql://localhost:5432/mugsun_track\n");
		Files.writeString(dir.resolve("local.yml"),
			"mybatis-flex.datasource.track.url: jdbc:postgresql://localhost:55532/mugsun_track\n");
		List<String> mapped = new ArrayList<>();
		for (String i : imports()) {
			if (i.equals(LOCAL)) {
				mapped.add("file:" + dir.resolve("local.yml"));
			} else if (i.contains("mugsun-module-track")) {
				mapped.add("file:" + dir.resolve("module.yml"));
			}
		}
		Files.writeString(dir.resolve("main.yml"), "spring.config.import:\n"
			+ String.join("", mapped.stream().map(m -> "  - " + m + "\n").toList()));
		try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(Empty.class)
			.web(WebApplicationType.NONE)
			.properties("spring.config.location=file:" + dir.resolve("main.yml"), "spring.main.banner-mode=off")
			.run()) {
			assertThat(ctx.getEnvironment().getProperty("mybatis-flex.datasource.track.url"))
				.isEqualTo("jdbc:postgresql://localhost:55532/mugsun_track");
		}
	}

	// 不加 @Configuration：否则会被 MugsunApplication 的组件扫描收进集成测试上下文（见 TestComponentScanGuardTest）
	static class Empty {
	}
}
