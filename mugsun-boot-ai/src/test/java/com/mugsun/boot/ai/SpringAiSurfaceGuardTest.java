package com.mugsun.boot.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B89-0 守卫：Spring AI 只放行四个基础包（模型接口、对话客户端，和它们带的两个公共包）；内置 MCP Server 仍然下线（加鉴权后恢复）。
 * <p>这四个包没有自动装配、不开端点、自己不连外网。Spring AI 的 MCP Server、自动装配和各家模型的接入包不一样：
 * jar 一回到 classpath、配置一开，就多出不经过本项目鉴权的端点，或者绕过出站白名单的连接。
 * 这里从四个方向确认进来的只有放行的那四个：classpath 上的包和类、自动装配候选、配置项、POM 声明。
 * 依赖层面另有根 pom 的 maven-enforcer bannedDependencies（{@code scripts/check-banned-dependencies.sh} 证明会触发）。
 */
class SpringAiSurfaceGuardTest {

	/** 放行的四个包，和根 pom enforcer 规则的 includes 一致 */
	private static final List<String> ALLOWED = List.of(
		"spring-ai-model", "spring-ai-client-chat", "spring-ai-commons", "spring-ai-template-st");

	/** POM 里直接声明的只有这两个，另外两个是它们带进来的 */
	private static final Set<String> DECLARED = Set.of("spring-ai-model", "spring-ai-client-chat");

	private static final Pattern SPRING_AI_JAR = Pattern.compile("/(spring-ai-[a-z0-9-]+?)-\\d[^/!]*\\.jar!");

	private static final Pattern SPRING_AI_DEPENDENCY = Pattern.compile(
		"<groupId>org\\.springframework\\.ai</groupId>\\s*<artifactId>([^<]+)</artifactId>");

	@ParameterizedTest(name = "{0} 不在 classpath 上")
	@ValueSource(strings = {
		// MCP Server 的自动装配和 MCP 客户端工具
		"org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration",
		"org.springframework.ai.mcp.server.autoconfigure.McpServerAutoConfiguration",
		"org.springframework.ai.mcp.SyncMcpToolCallbackProvider",
		// 自动装配：对话客户端、工具、各家模型、向量库
		"org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration",
		"org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration",
		"org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
		"org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration",
		// 各家模型的接入包（自带 HTTP 客户端，不过出站白名单）和向量库
		"org.springframework.ai.openai.OpenAiChatModel",
		"org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor",
		"org.springframework.ai.vectorstore.VectorStore",
		// MCP SDK
		"io.modelcontextprotocol.server.McpServer",
		"io.modelcontextprotocol.json.McpJsonMapper",
		"io.modelcontextprotocol.spec.McpSchema"})
	void classAbsent(String className) {
		assertThat(isPresent(className)).isFalse();
	}

	@Test
	@DisplayName("classpath 上的 Spring AI 包正好是放行的四个，没有 MCP SDK")
	void onlyAllowedSpringAiJarsOnClasspath() throws IOException {
		ClassLoader loader = getClass().getClassLoader();
		Set<String> springAi = new TreeSet<>();
		List<String> mcp = new ArrayList<>();
		// 每个 jar 都有清单文件，按它把 classpath 上的 jar 列出来（不读 java.class.path：测试进程里它可能只有一个启动用的 jar）
		for (URL url : Collections.list(loader.getResources("META-INF/MANIFEST.MF"))) {
			String location = url.toString().replace('\\', '/');
			Matcher jar = SPRING_AI_JAR.matcher(location);
			if (jar.find()) {
				springAi.add(jar.group(1));
			} else if (location.contains("/org/springframework/ai/")) {
				springAi.add(location);
			}
			if (location.contains("/io/modelcontextprotocol/")) {
				mcp.add(location);
			}
		}
		assertThat(springAi).as("多了：先过安全评审，再改根 pom 的 includes 和这里；少了：依赖变了，两处跟着改")
			.containsExactlyInAnyOrderElementsOf(ALLOWED);
		assertThat(mcp).isEmpty();
		assertThat(loader.getResource("io/modelcontextprotocol")).isNull();
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
	@DisplayName("AI 模块配置里没有 spring.ai.*（没有自动装配，这些配置不起作用；MUGSUN_AI_MCP_SERVER 等变量也不映射到任何配置）")
	void noSpringAiProperties() {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("mugsun-module-ai.yml"));
		Properties props = yaml.getObject();
		assertThat(props).isNotNull();
		assertThat(props.stringPropertyNames()).noneMatch(k -> k.startsWith("spring.ai"));
		assertThat(props.values()).map(String::valueOf).noneMatch(v -> v.contains("MUGSUN_AI_MCP"));
	}

	@Test
	@DisplayName("POM 里只有根 pom 和 AI 模块声明 Spring AI，而且只声明模型接口和对话客户端两个包；没有 MCP，没有 spring-ai-bom")
	void pomsDeclareOnlyAllowedSpringAi() throws IOException {
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
			String name = root.relativize(pom).toString().replace('\\', '/');
			boolean mayDeclare = name.equals("pom.xml") || name.equals("mugsun-boot-ai/pom.xml");
			String xml = Files.readString(pom, StandardCharsets.UTF_8)
				.replaceAll("(?s)<!--.*?-->", "")
				.replaceAll("(?s)<bannedDependencies>.*?</bannedDependencies>", "");
			if (xml.contains("modelcontextprotocol")) {
				hits.add(name + " : modelcontextprotocol");
			}
			Set<String> declared = new TreeSet<>();
			Matcher dependency = SPRING_AI_DEPENDENCY.matcher(xml);
			while (dependency.find()) {
				declared.add(dependency.group(1));
			}
			if (mayDeclare) {
				assertThat(declared).as(name + " 声明的 Spring AI 包").containsExactlyInAnyOrderElementsOf(DECLARED);
			} else if (!declared.isEmpty()) {
				hits.add(name + " : " + declared);
			}
			// 去掉上面认得的写法和版本号属性，剩下的里不该再提到 Spring AI（比如 import spring-ai-bom、写在插件里）
			String rest = SPRING_AI_DEPENDENCY.matcher(xml).replaceAll("")
				.replaceAll("<spring-ai\\.version>[^<]*</spring-ai\\.version>", "")
				.replace("${spring-ai.version}", "");
			if (rest.contains("org.springframework.ai") || rest.contains("spring-ai")) {
				hits.add(name + " : 用别的写法引用了 Spring AI");
			}
		}
		assertThat(hits).isEmpty();

		String rootPom = Files.readString(root.resolve("pom.xml"), StandardCharsets.UTF_8);
		Matcher banned = Pattern.compile("(?s)<bannedDependencies>(.*?)</bannedDependencies>").matcher(rootPom);
		assertThat(banned.find()).as("enforcer 的禁用规则仍在").isTrue();
		String rule = banned.group(1).replaceAll("(?s)<!--.*?-->", "");
		assertThat(rule).as("整个 Spring AI 和 MCP SDK 默认禁用").contains("<exclude>org.springframework.ai</exclude>")
			.contains("<exclude>io.modelcontextprotocol*</exclude>");
		List<String> includes = new ArrayList<>();
		Matcher include = Pattern.compile("<include>([^<]+)</include>").matcher(rule);
		while (include.find()) {
			includes.add(include.group(1).trim());
		}
		assertThat(includes).as("放行清单和这里的四个包一致")
			.containsExactlyInAnyOrderElementsOf(ALLOWED.stream().map(a -> "org.springframework.ai:" + a).toList());
	}

	private static boolean isPresent(String className) {
		try {
			Class.forName(className, false, SpringAiSurfaceGuardTest.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}
}
