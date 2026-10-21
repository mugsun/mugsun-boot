package com.mugsun.boot.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B89-0 反向守卫：Spring AI 不得回到依赖里；B2 起 MCP 只允许官方 SDK 的 mcp-core / mcp-json-jackson2 2.1.0（仅 server 端、Streamable HTTP），
 * 不得用 client、stdio、SSE 传输，也不得用 Spring AI 的 MCP 自动装配。
 * <p>Spring AI 的 MCP Server 自动装配不经过本项目鉴权，jar 一回到 classpath、配置一开就是匿名可调的端点。
 * 这里从四个方向确认它们没有回来：类、自动装配候选、配置项、POM 声明（含 BOM）。
 * 依赖层面另有根 pom 的 maven-enforcer bannedDependencies（{@code scripts/check-banned-dependencies.sh} 证明会触发）。
 */
class NoSpringAiGuardTest {

	@ParameterizedTest(name = "{0} 不在 classpath 上")
	@ValueSource(strings = {
		// 原 #88 的「前提」类，现在反过来必须不存在
		"org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration",
		"org.springframework.ai.mcp.server.autoconfigure.McpServerAutoConfiguration",
		"org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
		"org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration",
		// spring-ai-advisors-vector-store 及其传递依赖
		"org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor",
		"org.springframework.ai.chat.client.ChatClient",
		"org.springframework.ai.vectorstore.VectorStore",
		"org.springframework.ai.model.ModelOptions",
		// MCP：只放行 jackson2，jackson3 实现不得出现
		"io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper"})
	void classAbsent(String className) {
		assertThat(isPresent(className)).isFalse();
	}

	@Test
	@DisplayName("classpath 上没有 Spring AI；io.modelcontextprotocol 只来自放行的两个 2.1.0 jar")
	void noSpringAiOrMcpOnClasspath() {
		String sep = File.separator;
		List<String> found = new ArrayList<>();
		for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
			String normalized = entry.replace(sep, "/");
			if (normalized.contains("/org/springframework/ai/")) {
				found.add(entry);
			}
			if (normalized.contains("/io/modelcontextprotocol/")
				&& !normalized.endsWith("/mcp-core-2.1.0.jar") && !normalized.endsWith("/mcp-json-jackson2-2.1.0.jar")) {
				found.add(entry);
			}
		}
		assertThat(found).isEmpty();
		assertThat(getClass().getClassLoader().getResource("org/springframework/ai")).isNull();
	}

	@Test
	@DisplayName("项目代码不引用 MCP client、stdio 与 SSE 传输（mcp-core 里有这些类，但不得接线）")
	void noClientStdioOrSseInSources() throws IOException {
		Path root = Path.of("..").toAbsolutePath().normalize();
		List<String> hits = new ArrayList<>();
		try (var files = Files.walk(root)) {
			for (Path f : files.filter(p -> p.toString().endsWith(".java") && p.toString().contains("/src/main/")).toList()) {
				String src = Files.readString(f, StandardCharsets.UTF_8);
				for (String bad : new String[] {"io.modelcontextprotocol.client", "StdioServerTransport", "SseServerTransport"}) {
					if (src.contains(bad)) {
						hits.add(root.relativize(f) + " : " + bad);
					}
				}
			}
		}
		assertThat(hits).isEmpty();
	}

	@Test
	@DisplayName("自动装配候选里没有 Spring AI 的类")
	void noSpringAiAutoConfigurationCandidates() {
		List<String> candidates = new ArrayList<>();
		ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).forEach(candidates::add);
		assertThat(candidates).isNotEmpty()
			.noneMatch(c -> c.startsWith("org.springframework.ai.") || c.startsWith("io.modelcontextprotocol."));
	}

	@Test
	@DisplayName("AI 模块配置里没有 spring.ai.*（MUGSUN_AI_MCP_SERVER 等变量不再映射到任何配置）")
	void noSpringAiProperties() {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("mugsun-module-ai.yml"));
		Properties props = yaml.getObject();
		assertThat(props).isNotNull();
		assertThat(props.stringPropertyNames()).noneMatch(k -> k.startsWith("spring.ai"));
		assertThat(props.values()).map(String::valueOf).noneMatch(v -> v.contains("MUGSUN_AI_MCP"));
	}

	@Test
	@DisplayName("各 POM 不声明 Spring AI；MCP 只允许 mcp-core / mcp-json-jackson2，版本只在根 pom 精确锁定")
	void pomsDoNotDeclareSpringAi() throws IOException {
		Path root = Path.of("..").toAbsolutePath().normalize();
		assertThat(root.resolve("pom.xml")).exists();
		List<Path> poms = new ArrayList<>(List.of(root.resolve("pom.xml")));
		try (var dirs = Files.list(root)) {
			dirs.filter(d -> Files.isRegularFile(d.resolve("pom.xml")) && d.getFileName().toString().startsWith("mugsun-boot-"))
				.forEach(d -> poms.add(d.resolve("pom.xml")));
		}
		assertThat(poms).hasSizeGreaterThan(4);
		List<String> hits = new ArrayList<>();
		for (Path pom : poms) {
			String xml = Files.readString(pom, StandardCharsets.UTF_8)
				.replaceAll("(?s)<!--.*?-->", "")
				.replaceAll("(?s)<bannedDependencies>.*?</bannedDependencies>", "")
				.replaceAll("<mcp-sdk.version>2\\.1\\.0</mcp-sdk.version>", "")
				.replaceAll("<dependency>\\s*<groupId>io\\.modelcontextprotocol\\.sdk</groupId>\\s*<artifactId>(mcp-core|mcp-json-jackson2)</artifactId>"
					+ "(\\s*<version>\\$\\{mcp-sdk\\.version}</version>)?\\s*</dependency>", "");
			for (String bad : new String[] {"org.springframework.ai", "spring-ai", "modelcontextprotocol"}) {
				if (xml.contains(bad)) {
					hits.add(root.relativize(pom) + " : " + bad);
				}
			}
		}
		assertThat(hits).isEmpty();
		String rootPom = Files.readString(root.resolve("pom.xml"), StandardCharsets.UTF_8);
		assertThat(rootPom).as("enforcer 禁用清单仍在").contains("<exclude>org.springframework.ai</exclude>")
			.contains("<exclude>io.modelcontextprotocol*</exclude>")
			.contains("<include>io.modelcontextprotocol.sdk:mcp-core:[2.1.0]</include>")
			.contains("<include>io.modelcontextprotocol.sdk:mcp-json-jackson2:[2.1.0]</include>");
	}

	private static boolean isPresent(String className) {
		try {
			Class.forName(className, false, NoSpringAiGuardTest.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}
}
