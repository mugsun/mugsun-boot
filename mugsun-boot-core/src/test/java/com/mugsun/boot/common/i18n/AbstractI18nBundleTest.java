package com.mugsun.boot.common.i18n;

import com.mugsun.core.i18n.source.MessageBundles;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守住一个模块的英文翻译文件（{@code i18n/<名字>_en.properties}）。文件里的键是代码里的中文原文：代码里改了一个字，
 * 这句翻译就悄悄失效，运行时不会报任何错。这里在构建时把这种情况拦下来。
 *
 * <p>每个带翻译文件的模块写一个子类，只要说明两件事：文件叫什么、本模块随便哪一个类（用来找到本模块编译出来的类文件）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractI18nBundleTest {

	/** 这个项目各模块的翻译文件都用这个开头：mugsun-boot_en、mugsun-boot-ai_en…… */
	private static final String FAMILY = "mugsun-boot";

	/** 带编号的占位：{0}，也包括写明了格式的 {0,number}、按数量选写法的 {0,choice,…} */
	private static final Pattern NUMBERED = Pattern.compile("\\{(\\d+)[,}]");
	/** 其它花括号：句子里本来就有的 {z}/{x}/{y} 这类字面内容 */
	private static final Pattern OTHER_BRACES = Pattern.compile("\\{(?!\\d)[^{}]*}");
	private static final Pattern CHINESE = Pattern.compile("[\\u4e00-\\u9fff]");

	private Map<String, String> english;

	/** 翻译文件名，比如 {@code mugsun-boot_en.properties} */
	protected abstract String file();

	/** 本模块 src/main 里的任意一个类。翻译文件里的原文只能出自这个类所在的模块 */
	protected abstract Class<?> anchor();

	@BeforeAll
	public void readTheFile() {
		// 只读这一个文件：类路径上别的 jar 也带着翻译文件（比如 mugsun-core 自己的），不归这里管
		String file = file();
		english = read(file::equals);
	}

	/** 只读文件名符合条件的翻译文件，返回英文那张表 */
	static Map<String, String> read(Predicate<String> fileName) {
		return load(fileName).of(Locale.ENGLISH);
	}

	/** 只读文件名符合条件的翻译文件 */
	static MessageBundles load(Predicate<String> fileName) {
		// 包一层而不是继承：PathMatchingResourcePatternResolver 找目录时会再调自己的 getResources，继承的话目录也被滤掉
		PathMatchingResourcePatternResolver all = new PathMatchingResourcePatternResolver();
		ResourcePatternResolver filtered = new ResourcePatternResolver() {
			@Override
			public Resource[] getResources(String locationPattern) throws IOException {
				return Arrays.stream(all.getResources(locationPattern))
					.filter(resource -> resource.getFilename() != null && fileName.test(resource.getFilename()))
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
		return MessageBundles.load(filtered, null);
	}

	@Test
	public void fileIsRead() {
		assertThat(english).as("翻译文件 i18n/%s 应该读得到内容", file()).isNotEmpty();
	}

	@Test
	public void everyKeyIsStillInTheCode() throws Exception {
		Set<String> gone = new TreeSet<>(english.keySet());
		gone.removeAll(stringsInCompiledClasses());

		assertThat(gone)
			.as("i18n/%s 里这些原文在本模块的代码里找不到（代码里改过字？或者这句其实是别的模块的？），翻译不会生效。"
				+ "把等号左边改成代码里现在的原文，或者删掉这一行", file())
			.isEmpty();
	}

	/** 运行时各模块的翻译文件合成一张表，同一句在两个文件里各配一遍的话，只有一个生效，改另一个没有用 */
	@Test
	public void noKeyRepeatsAnotherModulesFile() {
		String file = file();
		Set<String> repeated = new TreeSet<>(english.keySet());
		repeated.retainAll(read(name -> name.startsWith(FAMILY) && !name.equals(file)).keySet());

		assertThat(repeated)
			.as("i18n/%s 里这些原文在别的模块的翻译文件里已经配过，这里不用再配，删掉这几行", file)
			.isEmpty();
	}

	@Test
	public void translationsAreEnglishAndKeepThePlaceholders() {
		List<String> problems = new ArrayList<>();
		english.forEach((key, value) -> {
			if (CHINESE.matcher(value).find()) {
				problems.add("译文里还有中文：" + key + " = " + value);
			}
			if (!numbers(key).equals(numbers(value)) || !otherBraces(key).equals(otherBraces(value))) {
				problems.add("占位和原文对不上：" + key + " = " + value);
			}
			if (NUMBERED.matcher(key).find() && !fillsEveryPlaceholder(value)) {
				problems.add("占位填不进去（带 {0} 的译文里单引号要写两个）：" + key + " = " + value);
			}
		});

		assertThat(problems).as("i18n/%s 里有问题的译文", file()).isEmpty();
	}

	/**
	 * 用到了哪几个编号。只比编号、不比写法和次数：译文可以按数量分单复数
	 * （{@code {0} {0,choice,0#layers|1#layer|1<layers}}），同一个值会用到不止一次
	 */
	private static Set<String> numbers(String text) {
		Set<String> found = new TreeSet<>();
		Matcher matcher = NUMBERED.matcher(text);
		while (matcher.find()) {
			found.add(matcher.group(1));
		}
		return found;
	}

	private static List<String> otherBraces(String text) {
		List<String> found = new ArrayList<>();
		Matcher matcher = OTHER_BRACES.matcher(text);
		while (matcher.find()) {
			found.add(matcher.group());
		}
		found.sort(null);
		return found;
	}

	/**
	 * 带 {0} 的文案运行时按 MessageFormat 解析：单引号没写成两个的话，后面的占位会被当成普通文字，填不进值。
	 * 按数量选写法的占位只认数字，所以拿 0、1、2 各填一遍，把「没有、一个、多个」三种写法都走到
	 */
	private static boolean fillsEveryPlaceholder(String pattern) {
		try {
			MessageFormat format = new MessageFormat(pattern, Locale.ENGLISH);
			for (int count = 0; count <= 2; count++) {
				String filled = format.format(new Object[] {count, count, count, count, count, count});
				if (NUMBERED.matcher(filled).find()) {
					return false;
				}
			}
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** 这个模块编译出来的所有类里的字符串：代码里的字符串字面量、注解里写的提示语都在类文件的常量池里 */
	private Set<String> stringsInCompiledClasses() throws Exception {
		Path classes = Path.of(anchor().getProtectionDomain().getCodeSource().getLocation().toURI());
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
