package com.mugsun.boot.security.csp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把浏览器上报的 CSP 违规报告整理成可落日志的精简记录（纯函数，便于单测）。
 * <ul>
 *   <li>兼容两种格式：旧的 {@code report-uri}（{@code {"csp-report": {...}}}，Content-Type application/csp-report）
 *       和 Reporting API（{@code report-to}，数组，元素 {@code {"type":"csp-violation","url":..,"body":{..}}}）。</li>
 *   <li>所有 URL 字段只保留 scheme + host + port + path，<b>丢弃 query 和 fragment</b>：WebSocket 地址带 {@code ?token=}，
 *       页面地址也可能带一次性参数，落日志前必须去掉。{@code inline}/{@code eval}/{@code data} 等关键字原样保留。</li>
 *   <li>只取白名单字段，每个字段截到 {@value #MAX_FIELD} 字符，单次最多 {@value #MAX_REPORTS} 条；
 *       {@code script-sample}/{@code sample} 可能含页面内容，不记录。</li>
 * </ul>
 */
public final class CspReportSanitizer {

	static final int MAX_FIELD = 256;
	static final int MAX_REPORTS = 10;

	/** 浏览器对非 URL 的被拦资源给出的关键字 */
	private static final Set<String> KEYWORDS = Set.of("inline", "eval", "data", "blob", "self", "wasm-eval",
		"trusted-types-policy", "trusted-types-sink");

	private CspReportSanitizer() {
	}

	/** 解析并清洗；无法识别的 JSON 返回空列表 */
	public static List<Map<String, Object>> sanitize(ObjectMapper om, byte[] body) {
		List<Map<String, Object>> out = new ArrayList<>();
		JsonNode root;
		try {
			root = om.readTree(body);
		} catch (Exception e) {
			return out;
		}
		if (root == null) {
			return out;
		}
		if (root.isArray()) {
			for (JsonNode item : root) {
				if (out.size() >= MAX_REPORTS) {
					break;
				}
				if (!"csp-violation".equals(item.path("type").asText())) {
					continue;
				}
				JsonNode b = item.path("body");
				out.add(record(
					text(b, "documentURL", item.path("url").asText(null)),
					text(b, "blockedURL", null),
					text(b, "effectiveDirective", null),
					text(b, "sourceFile", null),
					b.path("lineNumber").isNumber() ? b.path("lineNumber").asInt() : null,
					text(b, "disposition", null)));
			}
			return out;
		}
		JsonNode r = root.path("csp-report");
		if (r.isObject()) {
			String directive = text(r, "effective-directive", text(r, "violated-directive", null));
			out.add(record(
				text(r, "document-uri", null),
				text(r, "blocked-uri", null),
				directive,
				text(r, "source-file", null),
				r.path("line-number").isNumber() ? r.path("line-number").asInt() : null,
				text(r, "disposition", null)));
		}
		return out;
	}

	private static Map<String, Object> record(String document, String blocked, String directive, String source,
											  Integer line, String disposition) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("document", stripUrl(document));
		m.put("blocked", stripUrl(blocked));
		m.put("directive", clip(directive));
		m.put("source", stripUrl(source));
		m.put("line", line);
		m.put("disposition", clip(disposition));
		return m;
	}

	/** 去掉 query / fragment / userinfo；关键字原样；解析不了就截到第一个 ? 或 # */
	public static String stripUrl(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String v = raw.trim();
		if (KEYWORDS.contains(v.toLowerCase())) {
			return v.toLowerCase();
		}
		int cut = indexOfAny(v, '?', '#');
		String noQuery = cut < 0 ? v : v.substring(0, cut);
		try {
			URI u = new URI(noQuery);
			if (u.getScheme() != null && u.getHost() != null) {
				String s = u.getScheme().toLowerCase() + "://" + u.getHost()
					+ (u.getPort() >= 0 ? ":" + u.getPort() : "")
					+ (u.getRawPath() == null ? "" : u.getRawPath());
				return clip(s);
			}
			if (u.getScheme() != null) {
				// data:/blob: 等：只留 scheme，内容可能就是页面数据
				return u.getScheme().toLowerCase();
			}
		} catch (Exception ignored) {
			// 落到下面的保守截断
		}
		int at = noQuery.indexOf('@');
		return clip(at < 0 ? noQuery : noQuery.substring(at + 1));
	}

	private static int indexOfAny(String s, char a, char b) {
		int i = s.indexOf(a);
		int j = s.indexOf(b);
		if (i < 0) {
			return j;
		}
		return j < 0 ? i : Math.min(i, j);
	}

	private static String text(JsonNode n, String field, String def) {
		JsonNode v = n.get(field);
		return v == null || v.isNull() ? def : v.asText();
	}

	private static String clip(String s) {
		if (s == null) {
			return null;
		}
		return s.length() <= MAX_FIELD ? s : s.substring(0, MAX_FIELD);
	}
}
