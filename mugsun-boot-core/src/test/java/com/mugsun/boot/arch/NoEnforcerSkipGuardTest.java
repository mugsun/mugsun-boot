package com.mugsun.boot.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
 * B89-3 守卫：仓库里任何地方都不能让 maven-enforcer 不执行。
 * <p>bannedDependencies（B89-0）只在 enforcer 执行时生效。下面任何一种写法都会让某个模块或某次构建绕过它，扫描到即失败：
 * <ul>
 * <li>pom：{@code enforcer.skip}（属性或引用）；enforcer 插件 / execution 上的 {@code <skip>}、{@code <fail>false</fail>}、
 * {@code <phase>none</phase>}（execution 不绑定生命周期）、{@code <inherited>false</inherited>}（子模块不继承）；</li>
 * <li>{@code .mvn/*.config}：{@code enforcer.skip}；</li>
 * <li>{@code .github/workflows/**}、{@code scripts/**}：{@code -Denforcer.skip}、{@code enforcer.skip=}、{@code <enforcer.skip>}。</li>
 * </ul>
 * <p>递归扫描整个仓库，跳过构建产物与工具目录（{@code target}、{@code node_modules}、{@code .git}、{@code .m2repo} 等）。
 * 唯一豁免见 {@link #ALLOWED_SCRIPT_LINES}：{@code scripts/check-banned-dependencies.sh} 的传递依赖探针必须生成一个
 * 「自己关掉 enforcer 的包装模块」来证明使用方仍被拦下，该行只写进 {@code target/enforcer-probe/} 下的临时工程。
 * 豁免按「文件 + 整行精确匹配 + 恰好一次」生效，同一文件里再多一处或别的文件里出现同样的行都仍然失败。
 */
class NoEnforcerSkipGuardTest {

	/** 不扫描的目录名：构建产物、依赖缓存、VCS 与 IDE 目录 */
	static final Set<String> SKIPPED_DIRS = Set.of("target", "node_modules", ".git", ".m2repo", ".m2", ".idea", ".vscode");

	/** 豁免：文件（相对仓库根）→ 允许恰好出现一次的整行（去掉首尾空白后比较） */
	static final java.util.Map<String, String> ALLOWED_SCRIPT_LINES = java.util.Map.of(
		"scripts/check-banned-dependencies.sh", "<enforcer.skip>true</enforcer.skip>");

	private static final Pattern ENFORCER_PLUGIN = Pattern.compile(
		"(?s)<plugin>(?:(?!</plugin>).)*?<artifactId>\\s*maven-enforcer-plugin\\s*</artifactId>.*?</plugin>");

	/** enforcer 插件块内让它不执行或不被子模块继承的写法 */
	static final Pattern PLUGIN_SKIP = Pattern.compile(
		"(?s)<skip>|<fail>\\s*false\\s*</fail>|<phase>\\s*none\\s*</phase>|<inherited>\\s*false\\s*</inherited>");

	/** CI 工作流与脚本里关掉 enforcer 的写法 */
	static final Pattern SCRIPT_SKIP = Pattern.compile("-D\\s*enforcer\\.skip|enforcer\\.skip\\s*=|<enforcer\\.skip>");

	@Test
	@DisplayName("仓库内 pom、.mvn 配置、CI 工作流与脚本都没有跳过 enforcer 的写法（探针那一行精确豁免）")
	void noPomSkipsEnforcer() throws IOException {
		Path root = Path.of("..").toAbsolutePath().normalize();
		assertThat(root.resolve("pom.xml")).as("从 mugsun-boot-core 往上一级应是仓库根").exists();
		List<Path> scanned = new ArrayList<>();
		List<String> hits = scan(root, scanned);
		assertThat(scanned).as("至少扫到根 pom、模块 pom、CI 工作流与探针脚本")
			.contains(root.resolve("pom.xml"), root.resolve("mugsun-boot-core/pom.xml"), root.resolve("mugsun-boot-server/pom.xml"),
				root.resolve(".github/workflows/ci.yml"), root.resolve("scripts/check-banned-dependencies.sh"));
		assertThat(hits).as("跳过 enforcer 会让 bannedDependencies（B89-0）失效").isEmpty();
	}

	@Test
	@DisplayName("扫描器自检：子模块 enforcer.skip、插件 <skip>、.mvn/maven.config 都能查出；target/ 下的探针与注释里的字样不算")
	void scannerCatchesSkipsAndIgnoresProbes(@TempDir Path root) throws IOException {
		write(root.resolve("pom.xml"), "<project><!-- enforcer.skip 只是注释 --><modules><module>a</module></modules></project>");
		write(root.resolve("a/pom.xml"), "<project><properties><enforcer.skip>true</enforcer.skip></properties></project>");
		write(root.resolve("b/pom.xml"), enforcerPom("<configuration><skip>true</skip></configuration>"));
		write(root.resolve("c/pom.xml"), "<project><build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId>"
			+ "<configuration><skip>true</skip></configuration></plugin><plugin><artifactId>maven-enforcer-plugin</artifactId>"
			+ "<executions><execution><id>x</id><phase>validate</phase><inherited>true</inherited></execution></executions></plugin></plugins></build></project>");
		write(root.resolve("target/enforcer-probe/transitive/wrapper/pom.xml"), "<project><properties><enforcer.skip>true</enforcer.skip></properties></project>");
		write(root.resolve("web/node_modules/x/pom.xml"), "<project><properties><enforcer.skip>true</enforcer.skip></properties></project>");
		write(root.resolve(".mvn/maven.config"), "-Denforcer.skip=true");

		List<Path> scanned = new ArrayList<>();
		List<String> hits = scan(root, scanned);

		assertThat(hits).containsExactlyInAnyOrder("a/pom.xml : enforcer.skip", "b/pom.xml : " + PLUGIN_HIT,
			".mvn/maven.config : enforcer.skip");
		assertThat(scanned).noneMatch(p -> p.toString().contains("target") || p.toString().contains("node_modules"));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
		"<executions><execution><id>b89</id><phase>none</phase></execution></executions>",
		"<executions><execution><id>b89</id><phase> none </phase></execution></executions>",
		"<inherited>false</inherited>",
		"<executions><execution><id>b89</id><inherited>false</inherited></execution></executions>",
		"<configuration><fail>false</fail></configuration>"})
	@DisplayName("enforcer 插件或 execution 上的 <phase>none</phase>、<inherited>false</inherited>、<fail>false</fail> 都查得出")
	void pluginPhaseNoneAndNotInheritedAreCaught(String fragment, @TempDir Path root) throws IOException {
		write(root.resolve("pom.xml"), enforcerPom(fragment));
		assertThat(scan(root, new ArrayList<>())).containsExactly("pom.xml : " + PLUGIN_HIT);
	}

	@Test
	@DisplayName("别的插件上的 <phase>none</phase> / <inherited>false</inherited> 不算")
	void otherPluginsMayUsePhaseNone(@TempDir Path root) throws IOException {
		write(root.resolve("pom.xml"), "<project><build><plugins><plugin><artifactId>maven-jar-plugin</artifactId><inherited>false</inherited>"
			+ "<executions><execution><id>default-jar</id><phase>none</phase></execution></executions></plugin>"
			+ "<plugin><artifactId>maven-enforcer-plugin</artifactId><executions><execution><id>b89</id></execution></executions></plugin>"
			+ "</plugins></build></project>");
		assertThat(scan(root, new ArrayList<>())).isEmpty();
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
		".github/workflows/ci.yml|        run: mvn -B verify -Denforcer.skip=true",
		".github/workflows/nested/x.yaml|  MAVEN_OPTS: \"-D enforcer.skip\"",
		"scripts/build.sh|mvn -Denforcer.skip package",
		"scripts/env.sh|export MAVEN_ARGS=enforcer.skip=true",
		"scripts/gen.sh|echo '<enforcer.skip>true</enforcer.skip>' > pom.xml"})
	@DisplayName("CI 工作流与脚本里的 -Denforcer.skip / enforcer.skip= / <enforcer.skip> 都查得出")
	void workflowsAndScriptsAreScanned(String spec, @TempDir Path root) throws IOException {
		String[] parts = spec.split("\\|", 2);
		write(root.resolve(parts[0]), "#!/usr/bin/env bash\n" + parts[1] + "\n");
		assertThat(scan(root, new ArrayList<>())).containsExactly(parts[0] + " : " + SCRIPT_HIT);
	}

	@Test
	@DisplayName("探针脚本豁免很窄：只放过那一行一次；多一处、换写法或别的文件里出现都仍然失败")
	void probeExemptionIsNarrow(@TempDir Path root) throws IOException {
		String probe = "scripts/check-banned-dependencies.sh";
		String allowed = "\t\t<enforcer.skip>true</enforcer.skip>\n";
		write(root.resolve(probe), "#!/usr/bin/env bash\n# 注释里说 enforcer.skip 不算\ncat > pom.xml <<POM\n" + allowed + "POM\n");
		assertThat(scan(root, new ArrayList<>())).as("恰好一次：放过").isEmpty();

		write(root.resolve(probe), "#!/usr/bin/env bash\n" + allowed + allowed);
		assertThat(scan(root, new ArrayList<>())).as("出现两次").containsExactly(probe + " : " + SCRIPT_HIT);

		write(root.resolve(probe), "#!/usr/bin/env bash\n" + allowed + "mvn -Denforcer.skip=true validate\n");
		assertThat(scan(root, new ArrayList<>())).as("豁免行之外又有 -Denforcer.skip").containsExactly(probe + " : " + SCRIPT_HIT);

		Files.delete(root.resolve(probe));
		write(root.resolve("scripts/other.sh"), "#!/usr/bin/env bash\n" + allowed);
		assertThat(scan(root, new ArrayList<>())).as("同样的行在别的文件里").containsExactly("scripts/other.sh : " + SCRIPT_HIT);
	}

	static final String PLUGIN_HIT = "maven-enforcer-plugin <skip>/<fail>false</fail>/<phase>none</phase>/<inherited>false</inherited>";

	static final String SCRIPT_HIT = "-Denforcer.skip / enforcer.skip= / <enforcer.skip>";

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
				String rel = root.relativize(file).toString().replace('\\', '/');
				String name = file.getFileName().toString();
				boolean pom = name.equals("pom.xml");
				boolean mvnConfig = rel.startsWith(".mvn/") && rel.indexOf('/', 5) < 0 && name.endsWith(".config");
				boolean ciOrScript = rel.startsWith(".github/workflows/") || rel.startsWith("scripts/");
				if (!pom && !mvnConfig && !ciOrScript) {
					return FileVisitResult.CONTINUE;
				}
				scanned.add(file);
				// 脚本目录里可能有二进制文件：按 UTF-8 宽松解码，非法字节替换掉即可
				String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
				if (pom) {
					text = text.replaceAll("(?s)<!--.*?-->", "");
					if (text.contains("enforcer.skip")) {
						hits.add(rel + " : enforcer.skip");
					}
					Matcher m = ENFORCER_PLUGIN.matcher(text);
					while (m.find()) {
						if (PLUGIN_SKIP.matcher(m.group()).find()) {
							hits.add(rel + " : " + PLUGIN_HIT);
							break;
						}
					}
				} else if (mvnConfig) {
					if (text.contains("enforcer.skip")) {
						hits.add(rel + " : enforcer.skip");
					}
				} else if (SCRIPT_SKIP.matcher(withoutAllowedLine(rel, text)).find()) {
					hits.add(rel + " : " + SCRIPT_HIT);
				}
				return FileVisitResult.CONTINUE;
			}
		});
		return hits;
	}

	/** 只对登记的文件、整行精确匹配、恰好出现一次时去掉那一行；否则原样返回（照常检查） */
	private static String withoutAllowedLine(String rel, String text) {
		String allowed = ALLOWED_SCRIPT_LINES.get(rel);
		if (allowed == null) {
			return text;
		}
		String[] lines = text.split("\n", -1);
		int index = -1;
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].strip().equals(allowed)) {
				if (index >= 0) {
					return text;
				}
				index = i;
			}
		}
		if (index < 0) {
			return text;
		}
		lines[index] = "";
		return String.join("\n", lines);
	}

	private static String enforcerPom(String inner) {
		return "<project><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>"
			+ "<artifactId>maven-enforcer-plugin</artifactId>" + inner + "</plugin></plugins></build></project>";
	}

	private static void write(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}
}
