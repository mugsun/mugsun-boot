package com.mugsun.boot.security;

import com.mugsun.core.tool.exception.ServiceException;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 安全工具：用户可控的排序列名/方向必须经此校验，拦截 orderBy 注入（引号/分号/括号/子查询等）。
 * <p>列名限合法标识符（字母/下划线开头，字母数字下划线，≤64）；方向限 ASC/DESC；可选白名单强约束。
 */
public final class SqlSafeUtil {

	private static final Pattern COLUMN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

	private SqlSafeUtil() {
	}

	/** 校验排序列名为合法标识符；非法抛异常，返回原值 */
	public static String safeColumn(String column) {
		if (column == null || !COLUMN.matcher(column.trim()).matches()) {
			throw new ServiceException("非法排序字段：" + column);
		}
		return column.trim();
	}

	/** 白名单强约束：列名须在允许集合内（再叠加标识符校验） */
	public static String safeColumn(String column, Set<String> allowed) {
		String c = safeColumn(column);
		if (allowed != null && !allowed.contains(c)) {
			throw new ServiceException("不允许的排序字段：" + column);
		}
		return c;
	}

	/** 校验排序方向，仅允许 ASC/DESC（大小写不敏感），空默认 ASC */
	public static String safeDirection(String direction) {
		if (direction == null || direction.isBlank()) {
			return "ASC";
		}
		String d = direction.trim().toUpperCase();
		if (!"ASC".equals(d) && !"DESC".equals(d)) {
			throw new ServiceException("非法排序方向：" + direction);
		}
		return d;
	}

	/** 组装安全 orderBy 片段：合法列名 + 方向 */
	public static String orderBy(String column, String direction) {
		return safeColumn(column) + " " + safeDirection(direction);
	}

	/** 白名单版安全 orderBy 片段 */
	public static String orderBy(String column, String direction, Set<String> allowed) {
		return safeColumn(column, allowed) + " " + safeDirection(direction);
	}
}
