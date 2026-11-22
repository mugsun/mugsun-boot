package com.mugsun.boot.common.i18n;

import com.mugsun.boot.common.web.DirectResponse;
import com.mugsun.core.i18n.source.MessageBundles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守住英文翻译文件 {@code i18n/mugsun-boot_en.properties}。文件里的键是代码里的中文原文：代码里改了一个字，
 * 这句翻译就悄悄失效，运行时不会报任何错。这里在构建时把这种情况拦下来。
 */
class I18nBundleTest {

	private static final String FILE = "mugsun-boot_en.properties";

	private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^{}]*}");
	private static final Pattern NUMBERED = Pattern.compile("\\{\\d+}");
	private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fff]");

	private static Map<String, String> english;

	@BeforeAll
	static void readTheFile() {
		// 只读这一个文件：类路径上别的 jar 也带着翻译文件（比如 mugsun-core 自己的），不归这里管。
		// 包一层而不是继承：PathMatchingResourcePatternResolver 找目录时会再调自己的 getResources，继承的话目录也被滤掉
		PathMatchingResourcePatternResolver all = new PathMatchingResourcePatternResolver();
		ResourcePatternResolver onlyThisFile = new ResourcePatternResolver() {
			@Override
			public Resource[] getResources(String locationPattern) throws IOException {
				return Arrays.stream(all.getResources(locationPattern))
					.filter(resource -> FILE.equals(resource.getFilename()))
					.toArray(Resource[]::new);
			}

			@Override
			public Resource getResource(String location) {
				return all.getResource(location);
			}

			@Override
			public ClassLoader getClassLoader() {
				return all.getClassLoader();
			}
		};
		english = MessageBundles.load(onlyThisFile, null).of(Locale.ENGLISH);
	}

	@Test
	void fileIsRead() {
		assertThat(english).as("翻译文件 i18n/%s 应该读得到内容", FILE).isNotEmpty();
	}

	@Test
	void everyKeyIsStillInTheCode() throws Exception {
		Set<String> gone = new TreeSet<>(english.keySet());
		gone.removeAll(stringsInCompiledClasses());

		assertThat(gone)
			.as("i18n/%s 里这些原文在代码里已经找不到了（代码里改过字？），翻译不会生效。"
				+ "把等号左边改成代码里现在的原文，或者删掉这一行", FILE)
			.isEmpty();
	}

	@Test
	void translationsAreEnglishAndKeepThePlaceholders() {
		List<String> problems = new ArrayList<>();
		english.forEach((key, value) -> {
			if (CHINESE.matcher(value).find()) {
				problems.add("译文里还有中文：" + key + " = " + value);
			}
			if (!placeholders(key).equals(placeholders(value))) {
				problems.add("占位和原文对不上：" + key + " = " + value);
			}
			if (NUMBERED.matcher(key).find() && !fillsEveryPlaceholder(value)) {
				problems.add("占位填不进去（带 {0} 的译文里单引号要写两个）：" + key + " = " + value);
			}
		});

		assertThat(problems).as("i18n/%s 里有问题的译文", FILE).isEmpty();
	}

	private static List<String> placeholders(String text) {
		List<String> found = new ArrayList<>();
		Matcher matcher = PLACEHOLDER.matcher(text);
		while (matcher.find()) {
			found.add(matcher.group());
		}
		found.sort(null);
		return found;
	}

	/** 带 {0} 的文案运行时按 MessageFormat 解析：单引号没写成两个的话，后面的占位会被当成普通文字，填不进值 */
	private static boolean fillsEveryPlaceholder(String pattern) {
		try {
			String filled = new MessageFormat(pattern, Locale.ENGLISH).format(new Object[] {"a", "b", "c", "d", "e", "f"});
			return !NUMBERED.matcher(filled).find();
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** 这个模块编译出来的所有类里的字符串：代码里的字符串字面量、注解里写的提示语都在类文件的常量池里 */
	private static Set<String> stringsInCompiledClasses() throws Exception {
		Path classes = Path.of(DirectResponse.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		assertThat(classes).as("模块编译输出目录").isDirectory();
		Set<String> strings = new HashSet<>();
		try (Stream<Path> files = Files.walk(classes)) {
			for (Path file : (Iterable<Path>) files.filter(path -> path.toString().endsWith(".class"))::iterator) {
				collectConstantPoolStrings(file, strings);
			}
		}
		return strings;
	}

	/** 按类文件格式读常量池，只收字符串（类型 1），其它类型按各自的长度跳过 */
	private static void collectConstantPoolStrings(Path classFile, Set<String> into) throws IOException {
		try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(classFile)))) {
			in.skipNBytes(8);
			int count = in.readUnsignedShort();
			for (int index = 1; index < count; index++) {
				int tag = in.readUnsignedByte();
				switch (tag) {
					case 1 -> into.add(in.readUTF());
					case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
					case 5, 6 -> {
						in.skipNBytes(8);
						// long 和 double 占两个序号
						index++;
					}
					case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
					case 15 -> in.skipNBytes(3);
					default -> throw new IOException("类文件常量池里有不认识的类型 " + tag + "：" + classFile);
				}
			}
		}
	}
}
