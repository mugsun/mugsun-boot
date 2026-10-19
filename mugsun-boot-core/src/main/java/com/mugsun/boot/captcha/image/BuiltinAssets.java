package com.mugsun.boot.captcha.image;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 判断某个素材目录是否在用仓库自带素材：目录就是默认 classpath 目录，或其中任一文件的 SHA-256 与自带素材相同
 * （把自带文件拷到 file: 目录同样算）。仓库素材公开，可被预建答案表 / 做差，生产须换私有图库。
 */
public final class BuiltinAssets {

	public static final String BG = "classpath:captcha/bg/";
	public static final String ROTATE = "classpath:captcha/rotate/";
	public static final String ICONS = "classpath:captcha/icons/";

	private static final String[] PATTERNS = {"*.jpg", "*.jpeg", "*.png", "*.JPG", "*.JPEG", "*.PNG"};
	private static final PathMatchingResourcePatternResolver RESOLVER = new PathMatchingResourcePatternResolver();
	private static final Map<String, Set<String>> BUILTIN_HASHES = new ConcurrentHashMap<>();

	private BuiltinAssets() {
	}

	public static boolean isBuiltin(String dir, String builtinDir) {
		String d = norm(dir == null || dir.isBlank() ? builtinDir : dir);
		if (d.equals(norm(builtinDir))) {
			return true;
		}
		Set<String> builtin = BUILTIN_HASHES.computeIfAbsent(builtinDir, BuiltinAssets::hashes);
		for (String h : hashes(d)) {
			if (builtin.contains(h)) {
				return true;
			}
		}
		return false;
	}

	private static String norm(String dir) {
		String d = dir.trim();
		if (d.startsWith("classpath*:")) {
			d = "classpath:" + d.substring("classpath*:".length());
		}
		if (d.startsWith("classpath:/")) {
			d = "classpath:" + d.substring("classpath:/".length());
		}
		return d.endsWith("/") ? d : d + "/";
	}

	static Set<String> hashes(String dir) {
		Set<String> out = new HashSet<>();
		String base = norm(dir);
		for (String p : PATTERNS) {
			try {
				for (Resource r : RESOLVER.getResources(base + p)) {
					try (InputStream in = r.getInputStream()) {
						out.add(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes())));
					} catch (Exception ignored) {
						// 单个文件不可读：跳过
					}
				}
			} catch (Exception ignored) {
				// 目录不存在
			}
		}
		return out;
	}
}
