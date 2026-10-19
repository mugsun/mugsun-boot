package com.mugsun.boot.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B96-3 架构守卫：可选模块的总开关只能走 {@code @ConditionalOnModuleSwitch}（严格解析，非法值启动失败）。
 * <p>按源码扫描整个仓库（core 不依赖各模块，拿不到它们的类），以下任一情况失败：
 * <ul>
 * <li>AI / GIS / 埋点三个自动装配不再带 {@code @ConditionalOnModuleSwitch("mugsun.<模块>.enabled")}；</li>
 * <li>任一模块（{@code mugsun-boot-*}，core / server 除外）在 {@code AutoConfiguration.imports} 里登记的自动装配
 * 用了 {@code @ConditionalOnProperty} / {@code @ConditionalOnBooleanProperty} / {@code @ConditionalOnExpression}，
 * 或者没有 {@code @ConditionalOnModuleSwitch}；</li>
 * <li>模块自带的 {@code mugsun-module-*.yml} 声明了 {@code mugsun.<x>.enabled}，但本模块没有任何自动装配用
 * {@code @ConditionalOnModuleSwitch("mugsun.<x>.enabled")} 守住它（新加模块开关漏了注解）；</li>
 * <li>任何主代码里用 {@code @ConditionalOnProperty} / {@code @ConditionalOnBooleanProperty} 直接判断
 * {@code mugsun.<模块>.enabled} 这类模块开关（字面量写法，含 {@code prefix} + {@code name = "enabled"}）。</li>
 * </ul>
 * {@code mugsun.demo.enabled}（演示站模式，不是模块）不受约束。
 */
class ModuleSwitchGuardTest {

	/** 现有三个模块开关：自动装配源码（相对仓库根）→ 属性名 */
	static final Map<String, String> EXPECTED = Map.of(
		"mugsun-boot-ai/src/main/java/com/mugsun/boot/ai/AiAutoConfiguration.java", "mugsun.ai.enabled",
		"mugsun-boot-gis/src/main/java/com/mugsun/boot/gis/GisAutoConfiguration.java", "mugsun.gis.enabled",
		"mugsun-boot-track/src/main/java/com/mugsun/boot/track/TrackAutoConfiguration.java", "mugsun.track.enabled");

	/** 不是可选模块的工程 */
	static final java.util.Set<String> NON_MODULES = java.util.Set.of("mugsun-boot-core", "mugsun-boot-server");

	/** 不受约束的 {@code mugsun.*.enabled}：演示站模式不是模块开关 */
	static final java.util.Set<String> NOT_MODULE_SWITCHES = java.util.Set.of("mugsun.demo.enabled");

	static final String IMPORTS = "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

	private static final Pattern MODULE_SWITCH = Pattern.compile("@ConditionalOnModuleSwitch\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"\\s*\\)");
	private static final Pattern LEGACY_CONDITION = Pattern.compile("@(?:\\w+\\.)*(ConditionalOnProperty|ConditionalOnBooleanProperty|ConditionalOnExpression)\\b");
	private static final Pattern PROPERTY_CONDITION = Pattern.compile(
		"(?s)@(?:\\w+\\.)*(?:ConditionalOnProperty|ConditionalOnBooleanProperty)\\s*\\(([^)]*)\\)");
	private static final Pattern LITERAL_SWITCH = Pattern.compile("\"(mugsun\\.[a-z0-9-]+\\.enabled)\"");
	private static final Pattern PREFIX = Pattern.compile("prefix\\s*=\\s*\"(mugsun\\.[a-z0-9-]+)\"");
	private static final Pattern NAME_ENABLED = Pattern.compile("(?:name|value)\\s*=\\s*\\{?\\s*\"enabled\"");

	@Test
	@DisplayName("AI / GIS / 埋点自动装配都用 @ConditionalOnModuleSwitch，且不用 @ConditionalOnProperty 等旧写法")
	void threeModulesUseModuleSwitch() throws IOException {
		Path root = repoRoot();
		for (Map.Entry<String, String> e : EXPECTED.entrySet()) {
			Path file = root.resolve(e.getKey());
			assertThat(file).as(e.getKey()).exists();
			String src = Files.readString(file, StandardCharsets.UTF_8);
			assertThat(switchesIn(src)).as(e.getKey() + " 的模块开关注解").containsExactly(e.getValue());
			assertThat(LEGACY_CONDITION.matcher(src).find()).as(e.getKey() + " 不得改回 @ConditionalOnProperty 一类注解").isFalse();
		}
	}

	@Test
	@DisplayName("仓库内：每个模块开关都有 @ConditionalOnModuleSwitch 守着，没有绕开解析器的写法")
	void repositoryHasNoViolations() throws IOException {
		Path root = repoRoot();
		Map<String, String> found = new TreeMap<>();
		List<String> violations = scan(root, found);
		assertThat(violations).as("模块开关必须走 @ConditionalOnModuleSwitch（B96-3）").isEmpty();
		assertThat(found).as("扫到的模块开关覆盖现有三个").containsAllEntriesOf(EXPECTED);
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {
		"@ConditionalOnProperty(name = \"mugsun.x.enabled\", havingValue = \"true\", matchIfMissing = true)",
		"@ConditionalOnProperty(prefix = \"mugsun.x\", name = \"enabled\", matchIfMissing = true)",
		"@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(\"mugsun.x.enabled\")",
		"@ConditionalOnBooleanProperty(\"mugsun.x.enabled\")",
		"@ConditionalOnExpression(\"${mugsun.x.enabled:true}\")"})
	@DisplayName("扫描器自检：模块自动装配改回 @ConditionalOnProperty 一类注解会被查出")
	void catchesRevertToConditionalOnProperty(String annotation, @TempDir Path root) throws IOException {
		fakeModule(root, "x", annotation, true);
		assertThat(scan(root, new TreeMap<>())).isNotEmpty()
			.anyMatch(v -> v.startsWith("mugsun-boot-x/src/main/java/com/mugsun/boot/x/XAutoConfiguration.java"));
	}

	@Test
	@DisplayName("扫描器自检：新模块 yml 声明了开关但自动装配漏了注解，会被查出")
	void catchesNewSwitchWithoutAnnotation(@TempDir Path root) throws IOException {
		fakeModule(root, "x", "", true);
		assertThat(scan(root, new TreeMap<>())).contains(
			"mugsun-boot-x/src/main/java/com/mugsun/boot/x/XAutoConfiguration.java : 自动装配缺少 @ConditionalOnModuleSwitch",
			"mugsun-boot-x/src/main/resources/mugsun-module-x.yml : mugsun.x.enabled 没有任何自动装配用 @ConditionalOnModuleSwitch 守住");
	}

	@Test
	@DisplayName("扫描器自检：注解里的属性名写错（与 yml 声明的开关对不上）也会被查出")
	void catchesMismatchedProperty(@TempDir Path root) throws IOException {
		fakeModule(root, "x", "@ConditionalOnModuleSwitch(\"mugsun.y.enabled\")", true);
		assertThat(scan(root, new TreeMap<>()))
			.contains("mugsun-boot-x/src/main/resources/mugsun-module-x.yml : mugsun.x.enabled 没有任何自动装配用 @ConditionalOnModuleSwitch 守住");
	}

	@Test
	@DisplayName("扫描器自检：模块里其它 Bean 直接用 @ConditionalOnProperty 判断模块开关会被查出；mugsun.demo.enabled 与其它属性不算")
	void catchesPropertyConditionOnSwitchElsewhere(@TempDir Path root) throws IOException {
		fakeModule(root, "x", "@ConditionalOnModuleSwitch(\"mugsun.x.enabled\")", true);
		write(root.resolve("mugsun-boot-x/src/main/java/com/mugsun/boot/x/Job.java"),
			"package com.mugsun.boot.x;\n@ConditionalOnProperty(name = \"mugsun.x.enabled\", havingValue = \"true\")\nclass Job {}\n");
		write(root.resolve("mugsun-boot-core/src/main/java/com/mugsun/boot/demo/D.java"),
			"package com.mugsun.boot.demo;\n@ConditionalOnProperty(name = \"mugsun.demo.enabled\", havingValue = \"true\")\n"
				+ "@ConditionalOnProperty(prefix = \"mugsun.x.uv\", name = \"on-startup\")\nclass D {}\n");
		assertThat(scan(root, new TreeMap<>()))
			.containsExactly("mugsun-boot-x/src/main/java/com/mugsun/boot/x/Job.java : 用 @ConditionalOnProperty 判断模块开关 mugsun.x.enabled");
	}

	@Test
	@DisplayName("扫描器自检：写对了不报错")
	void cleanModulePasses(@TempDir Path root) throws IOException {
		fakeModule(root, "x", "@ConditionalOnModuleSwitch(\"mugsun.x.enabled\")", true);
		Map<String, String> found = new TreeMap<>();
		assertThat(scan(root, found)).isEmpty();
		assertThat(found).containsExactly(Map.entry("mugsun-boot-x/src/main/java/com/mugsun/boot/x/XAutoConfiguration.java", "mugsun.x.enabled"));
	}

	// ---- 扫描器 ----

	/**
	 * 扫描 {@code root} 下的 {@code mugsun-boot-*} 工程。
	 * @param found 输出：自动装配源码（相对路径）→ 它守住的模块开关
	 * @return 违规列表，形如 {@code 相对路径 : 原因}
	 */
	static List<String> scan(Path root, Map<String, String> found) throws IOException {
		List<String> violations = new ArrayList<>();
		List<Path> projects;
		try (Stream<Path> s = Files.list(root)) {
			projects = s.filter(Files::isDirectory)
				.filter(p -> p.getFileName().toString().startsWith("mugsun-boot-"))
				.sorted().toList();
		}
		for (Path project : projects) {
			String name = project.getFileName().toString();
			if (!NON_MODULES.contains(name)) {
				scanModule(root, project, found, violations);
			}
			scanPropertyConditions(root, project, violations);
		}
		return violations;
	}

	private static void scanModule(Path root, Path project, Map<String, String> found, List<String> violations) throws IOException {
		java.util.Set<String> guarded = new TreeSet<>();
		Path imports = project.resolve(IMPORTS);
		if (Files.exists(imports)) {
			for (String line : Files.readAllLines(imports, StandardCharsets.UTF_8)) {
				String cls = line.strip();
				if (cls.isEmpty() || cls.startsWith("#")) {
					continue;
				}
				Path src = project.resolve("src/main/java").resolve(cls.replace('.', '/') + ".java");
				String rel = rel(root, src);
				if (!Files.exists(src)) {
					violations.add(rel + " : AutoConfiguration.imports 登记的类找不到源码");
					continue;
				}
				String code = Files.readString(src, StandardCharsets.UTF_8);
				Matcher legacy = LEGACY_CONDITION.matcher(code);
				if (legacy.find()) {
					violations.add(rel + " : 自动装配用了 @" + legacy.group(1) + "，模块开关必须用 @ConditionalOnModuleSwitch");
				}
				List<String> switches = switchesIn(code);
				if (switches.isEmpty()) {
					violations.add(rel + " : 自动装配缺少 @ConditionalOnModuleSwitch");
				} else {
					guarded.addAll(switches);
					found.put(rel, switches.get(0));
				}
			}
		}
		try (Stream<Path> s = Files.list(project.resolve("src/main/resources")).filter(p -> p.getFileName().toString().matches("mugsun-module-.+\\.ya?ml"))) {
			for (Path yml : s.sorted().toList()) {
				for (String prop : declaredSwitches(yml)) {
					if (!guarded.contains(prop)) {
						violations.add(rel(root, yml) + " : " + prop + " 没有任何自动装配用 @ConditionalOnModuleSwitch 守住");
					}
				}
			}
		} catch (java.nio.file.NoSuchFileException ignored) {
			// 没有资源目录的工程不声明开关
		}
	}

	/** 主代码里用 @ConditionalOnProperty 直接判断 mugsun.<x>.enabled（演示站模式除外） */
	private static void scanPropertyConditions(Path root, Path project, List<String> violations) throws IOException {
		Path main = project.resolve("src/main/java");
		if (!Files.isDirectory(main)) {
			return;
		}
		List<Path> files;
		try (Stream<Path> s = Files.walk(main)) {
			files = s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
		}
		for (Path f : files) {
			String code = Files.readString(f, StandardCharsets.UTF_8);
			Matcher m = PROPERTY_CONDITION.matcher(code);
			while (m.find()) {
				String args = m.group(1);
				java.util.Set<String> props = new TreeSet<>();
				Matcher lit = LITERAL_SWITCH.matcher(args);
				while (lit.find()) {
					props.add(lit.group(1));
				}
				Matcher prefix = PREFIX.matcher(args);
				if (prefix.find() && NAME_ENABLED.matcher(args).find()) {
					props.add(prefix.group(1) + ".enabled");
				}
				props.removeAll(NOT_MODULE_SWITCHES);
				for (String p : props) {
					violations.add(rel(root, f) + " : 用 @ConditionalOnProperty 判断模块开关 " + p);
				}
			}
		}
	}

	/** yml 里声明的 mugsun.<x>.enabled */
	@SuppressWarnings("unchecked")
	static List<String> declaredSwitches(Path yml) throws IOException {
		List<String> out = new ArrayList<>();
		for (Object doc : new Yaml().loadAll(Files.readString(yml, StandardCharsets.UTF_8))) {
			if (!(doc instanceof Map<?, ?> top) || !(top.get("mugsun") instanceof Map<?, ?> mugsun)) {
				continue;
			}
			for (Map.Entry<?, ?> e : ((Map<Object, Object>) mugsun).entrySet()) {
				if (e.getValue() instanceof Map<?, ?> module && module.containsKey("enabled")) {
					String prop = "mugsun." + e.getKey() + ".enabled";
					if (!NOT_MODULE_SWITCHES.contains(prop)) {
						out.add(prop);
					}
				}
			}
		}
		return out;
	}

	static List<String> switchesIn(String code) {
		List<String> out = new ArrayList<>();
		Matcher m = MODULE_SWITCH.matcher(code);
		while (m.find()) {
			out.add(m.group(1));
		}
		return out;
	}

	private static Path repoRoot() {
		Path root = Path.of("..").toAbsolutePath().normalize();
		assertThat(root.resolve("pom.xml")).as("从 mugsun-boot-core 往上一级应是仓库根").exists();
		return root;
	}

	private static String rel(Path root, Path p) {
		return root.relativize(p).toString().replace('\\', '/');
	}

	private static void fakeModule(Path root, String m, String annotation, boolean withYml) throws IOException {
		String cap = Character.toUpperCase(m.charAt(0)) + m.substring(1);
		String base = "mugsun-boot-" + m;
		write(root.resolve(base + "/" + IMPORTS), "com.mugsun.boot." + m + "." + cap + "AutoConfiguration\n");
		write(root.resolve(base + "/src/main/java/com/mugsun/boot/" + m + "/" + cap + "AutoConfiguration.java"),
			"package com.mugsun.boot." + m + ";\n\n@AutoConfiguration\n" + annotation + "\npublic class " + cap + "AutoConfiguration {\n}\n");
		if (withYml) {
			write(root.resolve(base + "/src/main/resources/mugsun-module-" + m + ".yml"),
				"mugsun:\n  " + m + ":\n    enabled: ${MUGSUN_" + m.toUpperCase() + "_ENABLED:true}\n    other: 1\n");
		}
	}

	private static void write(Path p, String content) throws IOException {
		Files.createDirectories(p.getParent());
		Files.writeString(p, content, StandardCharsets.UTF_8);
	}
}
