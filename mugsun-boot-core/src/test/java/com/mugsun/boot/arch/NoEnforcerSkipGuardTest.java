package com.mugsun.boot.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B89-3 守卫：仓库里任何 pom 都不能跳过 maven-enforcer。
 * <p>bannedDependencies（B89-0）只在 enforcer 执行时生效；某个模块写上 {@code enforcer.skip}，
 * 或在 enforcer 插件上配 {@code <skip>} / {@code <fail>false</fail>}，Spring AI / MCP 就能从这个模块绕进来。
 * <p>扫描整个仓库（递归），跳过构建产物与工具目录（{@code target}、{@code node_modules}、{@code .git}、{@code .m2repo} 等）。
 * {@code scripts/check-banned-dependencies.sh} 的探针工程（其中一个故意设了 {@code enforcer.skip}）生成在 {@code target/enforcer-probe/} 下，因此不在扫描范围内。
 * 同时检查 {@code .mvn/maven.config}、{@code .mvn/jvm.config}，防止从命令行默认参数里关掉。
 */
class NoEnforcerSkipGuardTest {

	/** 不扫描的目录名：构建产物、依赖缓存、VCS 与 IDE 目录 */
	static final Set<String> SKIPPED_DIRS = Set.of("target", "node_modules", ".git", ".m2repo", ".m2", ".idea", ".vscode");

	private static final Pattern ENFORCER_PLUGIN = Pattern.compile(
		"(?s)<plugin>(?:(?!</plugin>).)*?<artifactId>\\s*maven-enforcer-plugin\\s*</artifactId>.*?</plugin>");

	private static final Pattern PLUGIN_SKIP = Pattern.compile("(?s)<skip>|<fail>\\s*false\\s*</fail>");

	@Test
	@DisplayName("仓库内所有 pom 与 .mvn 配置都不含 enforcer.skip，enforcer 插件也没有 <skip> / <fail>false</fail>")
	void noPomSkipsEnforcer() throws IOException {
		Path root = Path.of("..").toAbsolutePath().normalize();
		assertThat(root.resolve("pom.xml")).as("从 mugsun-boot-core 往上一级应是仓库根").exists();
		List<Path> scanned = new ArrayList<>();
		List<String> hits = scan(root, scanned);
		assertThat(scanned).as("至少扫到根 pom 与五个模块 pom")
			.contains(root.resolve("pom.xml"), root.resolve("mugsun-boot-core/pom.xml"), root.resolve("mugsun-boot-server/pom.xml"))
			.hasSizeGreaterThanOrEqualTo(6);
		assertThat(hits).as("跳过 enforcer 会让 bannedDependencies（B89-0）失效").isEmpty();
	}

	@Test
	@DisplayName("扫描器自检：子模块里的 enforcer.skip、插件上的 <skip>、.mvn/maven.config 都能查出；target/ 下的探针与注释里的字样不算")
	void scannerCatchesSkipsAndIgnoresProbes(@TempDir Path root) throws IOException {
		write(root.resolve("pom.xml"), "<project><!-- enforcer.skip 只是注释 --><modules><module>a</module></modules></project>");
		write(root.resolve("a/pom.xml"), "<project><properties><enforcer.skip>true</enforcer.skip></properties></project>");
		write(root.resolve("b/pom.xml"), "<project><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>"
			+ "<artifactId>maven-enforcer-plugin</artifactId><configuration><skip>true</skip></configuration></plugin></plugins></build></project>");
		write(root.resolve("c/pom.xml"), "<project><build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId>"
			+ "<configuration><skip>true</skip></configuration></plugin><plugin><artifactId>maven-enforcer-plugin</artifactId>"
			+ "<executions><execution><id>x</id></execution></executions></plugin></plugins></build></project>");
		write(root.resolve("target/enforcer-probe/transitive/wrapper/pom.xml"), "<project><properties><enforcer.skip>true</enforcer.skip></properties></project>");
		write(root.resolve("web/node_modules/x/pom.xml"), "<project><properties><enforcer.skip>true</enforcer.skip></properties></project>");
		write(root.resolve(".mvn/maven.config"), "-Denforcer.skip=true");

		List<Path> scanned = new ArrayList<>();
		List<String> hits = scan(root, scanned);

		assertThat(hits).containsExactlyInAnyOrder("a/pom.xml : enforcer.skip", "b/pom.xml : maven-enforcer-plugin <skip>/<fail>false</fail>",
			".mvn/maven.config : enforcer.skip");
		assertThat(scanned).noneMatch(p -> p.toString().contains("target") || p.toString().contains("node_modules"));
	}

	static List<String> scan(Path root, List<Path> scanned) throws IOException {
		List<String> hits = new ArrayList<>();
		Files.walkFileTree(root, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
				if (!dir.equals(root) && SKIPPED_DIRS.contains(dir.getFileName().toString())) {
					return FileVisitResult.SKIP_SUBTREE;
				}
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				String name = file.getFileName().toString();
				boolean pom = name.equals("pom.xml");
				boolean mvnConfig = file.getParent() != null && file.getParent().getFileName() != null
					&& file.getParent().getFileName().toString().equals(".mvn") && name.endsWith(".config");
				if (!pom && !mvnConfig) {
					return FileVisitResult.CONTINUE;
				}
				scanned.add(file);
				String rel = root.relativize(file).toString().replace('\\', '/');
				String text = Files.readString(file, StandardCharsets.UTF_8);
				if (pom) {
					text = text.replaceAll("(?s)<!--.*?-->", "");
				}
				if (text.contains("enforcer.skip")) {
					hits.add(rel + " : enforcer.skip");
				}
				if (pom) {
					Matcher m = ENFORCER_PLUGIN.matcher(text);
					while (m.find()) {
						if (PLUGIN_SKIP.matcher(m.group()).find()) {
							hits.add(rel + " : maven-enforcer-plugin <skip>/<fail>false</fail>");
							break;
						}
					}
				}
				return FileVisitResult.CONTINUE;
			}
		});
		return hits;
	}

	private static void write(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}
}
