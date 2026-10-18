package com.mugsun.boot.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B89 白名单守卫：maven-enforcer 的 bannedDependencies 配置只能有一种写法。
 * <p>{@link NoEnforcerSkipGuardTest} 按黑名单查「跳过 enforcer」的已知写法；本类反过来只放行预期的那一份配置，
 * 防止经 {@code <includes>}、{@code combine.self}、{@code <alwaysPass>}、属性引用、{@code pluginManagement} / profile 覆盖等方式改写规则：
 * <ul>
 * <li>enforcer 插件只能出现在根 pom，且恰好一次，不能在 {@code <pluginManagement>} 或 {@code <profiles>} 里；</li>
 * <li>根 pom 必须定义 execution {@code b89-banned-dependencies}；</li>
 * <li>插件块里不能有 {@code <includes>}、{@code combine.self=}、{@code <alwaysPass}、属性引用 {@code ${...}}；</li>
 * <li>整个插件块（去掉注释、标签之间的空白后）必须与 {@link #EXPECTED_PLUGIN} 完全一致，其中 execution 显式绑定 validate。</li>
 * </ul>
 * 生效配置另由 {@code scripts/check-effective-enforcer.sh}（CI）对 mugsun-boot-server 的 help:effective-pom 再查一遍。
 * <p>修改规则须同时改这里的 {@link #EXPECTED_PLUGIN}，评审能在一处看到全部变化。
 */
class EnforcerAllowlistGuardTest {

	static final String EXECUTION_ID = "b89-banned-dependencies";

	/** 根 pom 里 enforcer 插件块的唯一合法内容（规范化后比较，见 {@link #normalize}） */
	static final String EXPECTED_PLUGIN = normalize("""
		<plugin>
			<groupId>org.apache.maven.plugins</groupId>
			<artifactId>maven-enforcer-plugin</artifactId>
			<executions>
				<execution>
					<id>b89-banned-dependencies</id>
					<phase>validate</phase>
					<goals>
						<goal>enforce</goal>
					</goals>
					<configuration>
						<rules>
							<bannedDependencies>
								<searchTransitive>true</searchTransitive>
								<excludes>
									<exclude>org.springframework.ai</exclude>
									<exclude>io.modelcontextprotocol*</exclude>
								</excludes>
								<message>B89-0：禁止引入 Spring AI（org.springframework.ai:*）与 MCP SDK（io.modelcontextprotocol*）。内置 MCP Server 已移除；它的端点不经过本项目鉴权，重新引入前须先过安全评审。</message>
							</bannedDependencies>
						</rules>
						<fail>true</fail>
					</configuration>
				</execution>
			</executions>
		</plugin>
		""");

	private static final Pattern ENFORCER_ARTIFACT = Pattern.compile("<artifactId>\\s*maven-enforcer-plugin\\s*</artifactId>");
	private static final Pattern ENFORCER_PLUGIN = Pattern.compile(
		"(?s)<plugin>(?:(?!</plugin>).)*?<artifactId>\\s*maven-enforcer-plugin\\s*</artifactId>.*?</plugin>");
	private static final Pattern FORBIDDEN_IN_PLUGIN = Pattern.compile("<includes[\\s>/]|combine\\.self\\s*=|<alwaysPass");

	static final String V_NOT_ROOT = "enforcer 插件只能写在根 pom";
	static final String V_COUNT = "根 pom 里 enforcer 插件应恰好出现一次";
	static final String V_PLUGIN_MGMT = "enforcer 插件不能写在 <pluginManagement> 里";
	static final String V_PROFILE = "enforcer 插件不能写在 <profiles> 里";
	static final String V_NO_EXECUTION = "根 pom 没有定义 execution " + EXECUTION_ID;
	static final String V_FORBIDDEN = "enforcer 插件块里出现 <includes> / combine.self= / <alwaysPass";
	static final String V_PROPERTY = "enforcer 插件块里不能用属性引用 ${...}";
	static final String V_MISMATCH = "enforcer 插件块与 EXPECTED_PLUGIN 不一致";

	@Test
	@DisplayName("仓库里的 enforcer 配置符合白名单")
	void repositoryMatchesAllowlist() throws IOException {
		Path root = Path.of("..").toAbsolutePath().normalize();
		assertThat(root.resolve("pom.xml")).as("从 mugsun-boot-core 往上一级应是仓库根").exists();
		assertThat(violations(root)).isEmpty();
	}

	static Stream<Arguments> tampered() {
		String plugin = rawPlugin();
		return Stream.of(
			Arguments.of("<includes> 放行被禁依赖", rootPom(plugin.replace("<excludes>",
				"<includes><include>org.springframework.ai:spring-ai-core</include></includes><excludes>")), List.of(V_FORBIDDEN, V_MISMATCH)),
			Arguments.of("combine.self 覆盖 excludes", rootPom(plugin.replace("<excludes>", "<excludes combine.self=\"override\">")),
				List.of(V_FORBIDDEN, V_MISMATCH)),
			Arguments.of("alwaysPass", rootPom(plugin.replace("<searchTransitive>", "<alwaysPass/><searchTransitive>")),
				List.of(V_FORBIDDEN, V_MISMATCH)),
			Arguments.of("phase 用属性引用", rootPom(plugin.replace("<phase>validate</phase>", "<phase>${b89.phase}</phase>")),
				List.of(V_PROPERTY, V_MISMATCH)),
			Arguments.of("fail 用属性引用", rootPom(plugin.replace("<fail>true</fail>", "<fail>${b89.fail}</fail>")),
				List.of(V_PROPERTY, V_MISMATCH)),
			Arguments.of("execution 改名", rootPom(plugin.replace(EXECUTION_ID, "b89-renamed")), List.of(V_NO_EXECUTION, V_MISMATCH)),
			Arguments.of("phase 改成 verify", rootPom(plugin.replace("<phase>validate</phase>", "<phase>verify</phase>")), List.of(V_MISMATCH)),
			Arguments.of("去掉 phase", rootPom(plugin.replace("<phase>validate</phase>", "")), List.of(V_MISMATCH)),
			Arguments.of("少一条 exclude", rootPom(plugin.replace("<exclude>io.modelcontextprotocol*</exclude>", "")), List.of(V_MISMATCH)),
			Arguments.of("插件级 configuration", rootPom(plugin.replace("</executions>", "</executions><configuration><rules/></configuration>")),
				List.of(V_MISMATCH)),
			Arguments.of("没有 enforcer 插件", rootPom(""), List.of(V_COUNT, V_NO_EXECUTION)),
			Arguments.of("pluginManagement 里再写一份", rootPom(plugin)
				.replace("<build>", "<build><pluginManagement><plugins>" + minimalPlugin() + "</plugins></pluginManagement>"),
				List.of(V_COUNT, V_PLUGIN_MGMT)),
			Arguments.of("profile 里再写一份", rootPom(plugin)
				.replace("</project>", "<profiles><profile><id>x</id><build><plugins>" + minimalPlugin()
					+ "</plugins></build></profile></profiles></project>"),
				List.of(V_COUNT, V_PROFILE))
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("tampered")
	@DisplayName("各种改写方式都被拦下")
	void tamperingIsCaught(String label, String rootPom, List<String> expected, @TempDir Path root) throws IOException {
		write(root.resolve("pom.xml"), rootPom);
		assertThat(violations(root)).containsExactlyInAnyOrderElementsOf(expected.stream().map(v -> "pom.xml : " + v).toList());
	}

	@Test
	@DisplayName("对照：与 EXPECTED_PLUGIN 一致的根 pom 通过；注释与缩进不同不影响")
	void expectedPasses(@TempDir Path root) throws IOException {
		String plugin = rawPlugin().replace("<searchTransitive>", "<!-- 说明 combine.self= <includes> ${x} -->\n\t\t<searchTransitive>");
		write(root.resolve("pom.xml"), rootPom(plugin));
		write(root.resolve("a/pom.xml"), "<project><!-- 不要在这里写 maven-enforcer-plugin --><artifactId>a</artifactId></project>");
		assertThat(violations(root)).isEmpty();
	}

	@Test
	@DisplayName("子模块 pom 写 enforcer 插件或引用 b89 execution 都失败；target/ 下的探针工程不算")
	void childPomsMayNotDeclareEnforcer(@TempDir Path root) throws IOException {
		write(root.resolve("pom.xml"), rootPom(rawPlugin()));
		write(root.resolve("a/pom.xml"), "<project><build><plugins>" + minimalPlugin() + "</plugins></build></project>");
		write(root.resolve("b/pom.xml"), "<project><build><plugins><plugin><artifactId>x</artifactId><executions><execution>"
			+ "<id>b89-banned-dependencies</id></execution></executions></plugin></plugins></build></project>");
		write(root.resolve("target/enforcer-probe/x/pom.xml"), "<project><build><plugins>" + minimalPlugin() + "</plugins></build></project>");
		assertThat(violations(root)).containsExactlyInAnyOrder("a/pom.xml : " + V_NOT_ROOT, "b/pom.xml : " + V_NOT_ROOT);
	}

	static List<String> violations(Path root) throws IOException {
		List<String> out = new ArrayList<>();
		String rootText = stripComments(Files.readString(root.resolve("pom.xml"), StandardCharsets.UTF_8));
		checkRoot(rootText, out);
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				if (!dir.equals(root) && NoEnforcerSkipGuardTest.SKIPPED_DIRS.contains(dir.getFileName().toString())) {
					return FileVisitResult.SKIP_SUBTREE;
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (!file.getFileName().toString().equals("pom.xml") || file.equals(root.resolve("pom.xml"))) {
					return FileVisitResult.CONTINUE;
				}
				String text = stripComments(Files.readString(file, StandardCharsets.UTF_8));
				if (text.contains("maven-enforcer-plugin") || text.contains(EXECUTION_ID)) {
					out.add(root.relativize(file).toString().replace('\\', '/') + " : " + V_NOT_ROOT);
				}
				return FileVisitResult.CONTINUE;
			}
		});
		return out;
	}

	private static void checkRoot(String text, List<String> out) {
		String p = "pom.xml : ";
		int count = 0;
		Matcher a = ENFORCER_ARTIFACT.matcher(text);
		while (a.find()) {
			count++;
		}
		if (count != 1) {
			out.add(p + V_COUNT);
		}
		if (blocks(text, "pluginManagement").stream().anyMatch(b -> ENFORCER_ARTIFACT.matcher(b).find())) {
			out.add(p + V_PLUGIN_MGMT);
		}
		if (blocks(text, "profiles").stream().anyMatch(b -> ENFORCER_ARTIFACT.matcher(b).find())) {
			out.add(p + V_PROFILE);
		}
		if (!text.contains("<id>" + EXECUTION_ID + "</id>")) {
			out.add(p + V_NO_EXECUTION);
		}
		if (count != 1) {
			return;
		}
		Matcher m = ENFORCER_PLUGIN.matcher(text);
		if (!m.find()) {
			return;
		}
		String plugin = m.group();
		if (FORBIDDEN_IN_PLUGIN.matcher(plugin).find()) {
			out.add(p + V_FORBIDDEN);
		}
		if (plugin.contains("${")) {
			out.add(p + V_PROPERTY);
		}
		if (!normalize(plugin).equals(EXPECTED_PLUGIN)) {
			out.add(p + V_MISMATCH);
		}
	}

	private static List<String> blocks(String text, String tag) {
		List<String> out = new ArrayList<>();
		Matcher m = Pattern.compile("(?s)<" + tag + "(?:\\s[^>]*)?>.*?</" + tag + ">").matcher(text);
		while (m.find()) {
			out.add(m.group());
		}
		return out;
	}

	static String stripComments(String text) {
		return text.replaceAll("(?s)<!--.*?-->", "");
	}

	/** 去注释、去标签之间的空白、去首尾空白 */
	static String normalize(String xml) {
		return stripComments(xml).replaceAll(">\\s+<", "><").strip();
	}

	private static String rawPlugin() {
		return """
			<plugin>
				<groupId>org.apache.maven.plugins</groupId>
				<artifactId>maven-enforcer-plugin</artifactId>
				<executions>
					<execution>
						<id>b89-banned-dependencies</id>
						<phase>validate</phase>
						<goals>
							<goal>enforce</goal>
						</goals>
						<configuration>
							<rules>
								<bannedDependencies>
									<searchTransitive>true</searchTransitive>
									<excludes>
										<exclude>org.springframework.ai</exclude>
										<exclude>io.modelcontextprotocol*</exclude>
									</excludes>
									<message>B89-0：禁止引入 Spring AI（org.springframework.ai:*）与 MCP SDK（io.modelcontextprotocol*）。内置 MCP Server 已移除；它的端点不经过本项目鉴权，重新引入前须先过安全评审。</message>
								</bannedDependencies>
							</rules>
							<fail>true</fail>
						</configuration>
					</execution>
				</executions>
			</plugin>
			""";
	}

	private static String minimalPlugin() {
		return "<plugin><artifactId>maven-enforcer-plugin</artifactId></plugin>";
	}

	private static String rootPom(String plugin) {
		return "<project><artifactId>root</artifactId><build><plugins><plugin><artifactId>spring-boot-maven-plugin</artifactId></plugin>"
			+ plugin + "</plugins></build></project>";
	}

	private static void write(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}
}
