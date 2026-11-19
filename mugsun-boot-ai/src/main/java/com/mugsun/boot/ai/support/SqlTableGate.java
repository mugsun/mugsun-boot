package com.mugsun.boot.ai.support;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 表白名单的解析。查询里引用了哪些表、在不在白名单内，由 {@link SqlReadOnlyChecker} 按语法解析后核对。
 */
public final class SqlTableGate {

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
}
