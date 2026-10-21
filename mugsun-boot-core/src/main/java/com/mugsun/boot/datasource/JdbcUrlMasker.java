package com.mugsun.boot.datasource;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记日志前给 JDBC URL 脱敏：去掉 {@code //user:pass@} 里的账号口令（含 Oracle thin 的 {@code user/pass@}），
 * 以及 {@code ?}、{@code &}、{@code ;} 后面名字像凭据的参数值（user、password、pwd、secret、token、key 等）。
 * 只用于日志，不改变实际连接用的 URL。
 */
public final class JdbcUrlMasker {

	static final String MASK = "***";

	/** {@code //} 之后、第一个 {@code /?;} 之前出现的 {@code xxx@}：userinfo */
	private static final Pattern USERINFO = Pattern.compile("(//)[^/?;@]*@");
	/** Oracle thin：{@code jdbc:oracle:thin:user/pass@host} */
	private static final Pattern ORACLE_THIN = Pattern.compile("(?i)(jdbc:oracle:thin:)[^@/]*/[^@]*@");
	/** 参数名包含凭据字样的键值对 */
	private static final Pattern CREDENTIAL_PARAM = Pattern.compile(
		"(?i)([?&;:]\\s*[\\w.\\-]*(?:user|password|passwd|pwd|secret|token|key|credential|auth)[\\w.\\-]*\\s*=)[^&;]*");

	private JdbcUrlMasker() {
	}

	public static String mask(String url) {
		if (url == null || url.isEmpty()) {
			return url;
		}
		String s = ORACLE_THIN.matcher(url).replaceFirst("$1" + Matcher.quoteReplacement(MASK) + "@");
		s = USERINFO.matcher(s).replaceFirst("$1" + Matcher.quoteReplacement(MASK) + "@");
		return CREDENTIAL_PARAM.matcher(s).replaceAll("$1" + Matcher.quoteReplacement(MASK));
	}
}
