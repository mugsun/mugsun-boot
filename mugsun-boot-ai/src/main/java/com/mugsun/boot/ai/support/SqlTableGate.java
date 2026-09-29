package com.mugsun.boot.ai.support;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.core.tool.exception.ServiceException;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 表白名单闸门：从 SELECT 粗提取 FROM/JOIN 表名，校验落在允许集合内。
 */
public final class SqlTableGate {

	private static final Pattern TABLE_REF = Pattern.compile(
		"(?i)\\b(?:from|join)\\s+([a-zA-Z0-9_\"`.]+)");

	private SqlTableGate() {
	}

	/** 解析逗号/分号/空白分隔的表白名单；空串返回 empty（调用方决定是否放行）。 */
	public static Set<String> parseWhitelist(String raw) {
		Set<String> out = new LinkedHashSet<>();
		if (raw == null || raw.isBlank()) {
			return out;
		}
		for (String part : raw.split("[,;\\s]+")) {
			if (!part.isBlank()) {
				out.add(part.trim().toLowerCase(Locale.ROOT));
			}
		}
		return out;
	}

	public static Set<String> extractTables(String sql) {
		Set<String> found = new LinkedHashSet<>();
		if (sql == null || sql.isBlank()) {
			return found;
		}
		Matcher m = TABLE_REF.matcher(sql);
		while (m.find()) {
			String raw = m.group(1).replace("\"", "").replace("`", "");
			int dot = raw.lastIndexOf('.');
			String name = (dot >= 0 ? raw.substring(dot + 1) : raw).toLowerCase(Locale.ROOT);
			found.add(name);
		}
		return found;
	}

	/**
	 * 若 whitelist 非空，SQL 中引用的表必须全部落在白名单内；找不到表引用亦拒绝。
	 */
	public static void assertAllowed(String sql, Set<String> whitelist) {
		if (whitelist == null || whitelist.isEmpty()) {
			return;
		}
		Set<String> found = extractTables(sql);
		if (found.isEmpty()) {
			throw new ServiceException(AiConstants.MSG_SQL_UNSAFE);
		}
		for (String t : found) {
			if (!whitelist.contains(t)) {
				throw new ServiceException("SQL 引用了未授权表: " + t);
			}
		}
	}
}
