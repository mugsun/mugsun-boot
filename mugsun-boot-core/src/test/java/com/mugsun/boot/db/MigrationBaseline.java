package com.mugsun.boot.db;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读合并后的迁移基线（测试用）。每套库只有一个 SQL 基线，由 {@code scripts/squash_flyway_baseline.py}
 * 把原来的脚本按版本顺序原样拼成，每段前面有三行来源标记；{@link #section} 按原文件名取出其中一段。
 */
public final class MigrationBaseline {

	/** 主库基线的文件名，PostgreSQL、达梦、openGauss 三个目录同名 */
	public static final String MAIN = "V1__baseline_0_2.sql";

	/** 埋点库基线的文件名 */
	public static final String TRACK = "T1__baseline_0_2.sql";

	private static final Pattern MARK = Pattern.compile("(?m)^-- ={20,}\\R-- 原 (\\S+)\\R-- ={20,}\\R");

	private MigrationBaseline() {
	}

	/** 整个主库基线；dir 取 migration、migration-dm 或 migration-og */
	public static String main(String dir) {
		return read("db/" + dir + "/" + MAIN);
	}

	/** 主库基线里来自 origName 的那一段，不含来源标记 */
	public static String section(String dir, String origName) {
		String all = main(dir);
		Matcher m = MARK.matcher(all);
		int start = -1;
		while (m.find()) {
			if (start >= 0) {
				return all.substring(start, m.start());
			}
			if (m.group(1).equals(origName)) {
				start = m.end();
			}
		}
		if (start < 0) {
			throw new AssertionError("db/" + dir + "/" + MAIN + " 里没有「原 " + origName + "」这一段");
		}
		return all.substring(start);
	}

	private static String read(String path) {
		try (InputStream in = MigrationBaseline.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) {
				throw new AssertionError("classpath 上没有 " + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
