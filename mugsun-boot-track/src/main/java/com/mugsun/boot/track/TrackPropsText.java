package com.mugsun.boot.track;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * props 文本工具：截断不拆代理对；按 UTF-8 字节截断；堆栈帧 URL 去 query。
 * 与 SDK（@mugsun/track-web core/utils：truncate / truncateBytes / stripStackUrlQuery）同口径。
 */
final class TrackPropsText {

	/** 帧 URL 的 query / hash（常带 token、签名）去掉，保留末尾 :行:列 */
	private static final Pattern STACK_URL_QUERY = Pattern.compile(
		"(\\b[A-Za-z][\\w+.-]*://[^\\s?#()]*)[?#][^\\s()]*?(:\\d+(?::\\d+)?)?(?=[\\s()]|$)", Pattern.MULTILINE);

	private TrackPropsText() {
	}

	/** 按 UTF-16 码元截断；截断点落在高位代理之后时再退一位，不留孤立代理 */
	static String truncate(String s, int max) {
		if (s == null || s.length() <= max) {
			return s;
		}
		int end = max;
		if (end > 0 && Character.isHighSurrogate(s.charAt(end - 1))) {
			end--;
		}
		return s.substring(0, end);
	}

	/** 按 UTF-8 字节截断，以码点为单位 */
	static String truncateUtf8(String s, int maxBytes) {
		if (s == null) {
			return null;
		}
		if (maxBytes <= 0) {
			return "";
		}
		int bytes = 0;
		int i = 0;
		while (i < s.length()) {
			int cp = s.codePointAt(i);
			int n = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
			if (bytes + n > maxBytes) {
				break;
			}
			bytes += n;
			i += Character.charCount(cp);
		}
		return s.substring(0, i);
	}

	static int utf8Length(String s) {
		return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
	}

	/** {@code https://cdn/app.js?token=x:10:5} → {@code https://cdn/app.js:10:5} */
	static String stripStackUrlQuery(String stack) {
		if (stack == null || stack.indexOf("://") < 0) {
			return stack;
		}
		Matcher m = STACK_URL_QUERY.matcher(stack);
		StringBuilder sb = new StringBuilder(stack.length());
		while (m.find()) {
			String pos = m.group(2) == null ? "" : m.group(2);
			m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + pos));
		}
		m.appendTail(sb);
		return sb.toString();
	}
}
