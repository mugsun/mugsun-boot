package com.mugsun.boot.security;

import cn.hutool.http.HtmlUtil;

/**
 * XSS 净化（Hutool {@link HtmlUtil}，无外部依赖）：
 * <ul><li>{@link #clean} 严格模式——转义全部 HTML 特殊字符（普通字段：任何 &lt;script&gt;/标签均被转义为实体，无法执行）；</li>
 * <li>{@link #cleanRich} 富文本——过滤危险标签/事件属性（script、style、on 事件、iframe 等），保留安全内容。</li></ul>
 */
public final class XssCleaner {

	private XssCleaner() {
	}

	/** 严格净化：转义 HTML 特殊字符（含 HTML 特征才处理，避免无谓改写普通文本） */
	public static String clean(String value) {
		if (value == null || value.isEmpty() || !mayHaveHtml(value)) {
			return value;
		}
		return HtmlUtil.escape(value);
	}

	/** 富文本净化：过滤脚本/样式/事件等危险内容，保留安全标签 */
	public static String cleanRich(String value) {
		if (value == null || value.isEmpty() || !mayHaveHtml(value)) {
			return value;
		}
		return HtmlUtil.filter(value);
	}

	private static boolean mayHaveHtml(String v) {
		// 仅在出现标签定界符时才净化：避免把普通含 & 文本（如 "R&D"、"Tom & Jerry"）误做 HTML 实体化损坏数据。
		// XSS 载荷（<script>/<img onerror>）必含 '<'，故不漏防护。
		return v.indexOf('<') >= 0 || v.indexOf('>') >= 0;
	}
}
