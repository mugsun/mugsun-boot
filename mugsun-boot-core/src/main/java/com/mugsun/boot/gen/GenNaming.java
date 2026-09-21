package com.mugsun.boot.gen;

import java.util.regex.Pattern;

/**
 * 代码生成命名约定：下划线↔驼峰↔短横线，表前缀剥离，数据库标识符校验。
 */
public final class GenNaming {

	/** 合法数据库标识符：小写字母起，字母/数字/下划线，≤63（PG 上限），防 DDL 注入 */
	private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_]{0,62}$");

	private GenNaming() {
	}

	/** 是否为合法数据库标识符（表名/列名白名单） */
	public static boolean isIdentifier(String s) {
		return s != null && IDENTIFIER.matcher(s).matches();
	}

	/** 剥离表前缀（大小写不敏感），如 gen_product / gen_ → product */
	public static String stripPrefix(String name, String prefix) {
		if (name != null && prefix != null && !prefix.isBlank()
			&& name.regionMatches(true, 0, prefix, 0, prefix.length())) {
			return name.substring(prefix.length());
		}
		return name;
	}

	/** 下划线转驼峰；upperFirst 控制首字母大小写（sys_dict_type → SysDictType / sysDictType） */
	public static String toCamel(String name, boolean upperFirst) {
		StringBuilder sb = new StringBuilder();
		boolean up = upperFirst;
		for (char c : name.toCharArray()) {
			if (c == '_') {
				up = true;
			} else if (up) {
				sb.append(Character.toUpperCase(c));
				up = false;
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	/** 下划线转短横线（dict_type → dict-type），用于前端路由/接口路径 */
	public static String toKebab(String name) {
		return name == null ? null : name.replace('_', '-');
	}

	/** 业务名：全小写、无下划线（gen_product → genproduct），对齐 validateTableMeta 白名单 */
	public static String toBusinessName(String stripped) {
		if (stripped == null || stripped.isBlank()) {
			return stripped;
		}
		return stripped.replace("_", "").toLowerCase();
	}
}
