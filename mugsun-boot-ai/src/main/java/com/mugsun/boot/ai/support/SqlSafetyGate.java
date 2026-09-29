package com.mugsun.boot.ai.support;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.core.tool.exception.ServiceException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 问数 SQL 闸门：仅允许单条 SELECT / WITH…SELECT，拒绝写操作、SELECT INTO、多语句。
 * <p>注意：Java 字符串中词边界必须写成 {@code \\b}，写成 {@code \b} 会变成退格符导致闸门失效。
 */
public final class SqlSafetyGate {

	/** 写操作 / 危险关键字（含 SELECT INTO 的 into） */
	private static final Pattern FORBIDDEN = Pattern.compile(
		"(?i)\\b(insert|update|delete|drop|alter|truncate|create|grant|revoke|call|exec|execute|merge|replace|into|outfile|load_file|copy|pg_sleep)\\b");

	private SqlSafetyGate() {
	}

	public static String guard(String sql) {
		if (sql == null || sql.isBlank()) {
			throw new ServiceException(AiConstants.MSG_SQL_UNSAFE);
		}
		String s = sql.trim();
		if (s.endsWith(";")) {
			s = s.substring(0, s.length() - 1).trim();
		}
		if (s.contains(";")) {
			throw new ServiceException(AiConstants.MSG_SQL_UNSAFE);
		}
		// 去掉行/块注释后再判，避免注释绕过
		String stripped = stripComments(s);
		String lower = stripped.toLowerCase(Locale.ROOT).trim();
		if (!(lower.startsWith("select") || lower.startsWith("with"))) {
			throw new ServiceException(AiConstants.MSG_SQL_UNSAFE);
		}
		if (FORBIDDEN.matcher(stripped).find()) {
			throw new ServiceException(AiConstants.MSG_SQL_UNSAFE);
		}
		if (Pattern.compile("(?i)\\bfor\\s+update\\b").matcher(stripped).find()) {
			throw new ServiceException(AiConstants.MSG_SQL_UNSAFE);
		}
		return s;
	}

	static String stripComments(String sql) {
		String noBlock = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
		StringBuilder sb = new StringBuilder();
		for (String line : noBlock.split("\n")) {
			int idx = line.indexOf("--");
			sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
		}
		return sb.toString();
	}
}
