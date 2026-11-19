package com.mugsun.boot.security;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 富文本净化，只用在内容本身就是 HTML 的字段上：公告、帮助文档、更新日志的正文，站内信正文。
 * 普通文字（标题、名称、SQL、代码、提示词、搜索条件）不过这里：原样保存，显示时由前端转义（BOOT-117）。
 *
 * <p>用 jsoup 按白名单清理：内容先解析成节点树，只留名单里的标签、属性和链接协议，script、事件属性、
 * javascript: 链接、iframe、表单都留不下来。名单以 {@link Safelist#relaxed()} 为底，补上编辑器（wangEditor）
 * 会输出的行内样式、代码块语言、待办、视频和图片上的链接。显示端另有 DOMPurify，这里是服务端的一道。
 */
public final class RichTextSanitizer {

	/** 相对地址要先补成绝对地址才能判协议；配合 preserveRelativeLinks，这个前缀不会写进输出 */
	private static final String BASE_URI = "http://localhost/";

	private static final Safelist SAFELIST = Safelist.relaxed()
		.addTags("hr", "s", "del", "ins", "mark", "video", "source", "input")
		.addAttributes(":all", "style")
		.addAttributes("a", "target")
		.addAttributes("img", "data-href")
		.addAttributes("code", "class")
		.addAttributes("div", "data-w-e-type", "data-w-e-is-void")
		.addAttributes("video", "poster", "controls", "width", "height")
		.addAttributes("source", "src", "type")
		.addAttributes("input", "checked")
		.addProtocols("a", "href", "tel")
		.addProtocols("img", "src", "data")
		.addProtocols("img", "data-href", "http", "https")
		.addProtocols("video", "poster", "http", "https")
		.addProtocols("source", "src", "http", "https")
		.addEnforcedAttribute("a", "rel", "noopener noreferrer")
		// input 只留给待办的勾选框：不管原来是什么类型，一律变成不可操作的 checkbox
		.addEnforcedAttribute("input", "type", "checkbox")
		.addEnforcedAttribute("input", "disabled", "disabled")
		.preserveRelativeLinks(true);

	/** 能让内容脱离正文、浮到页面其他位置上面的定位属性；其余样式都保留 */
	private static final Set<String> BLOCKED_STYLE_PROPERTIES = Set.of(
		"position", "z-index", "top", "right", "bottom", "left",
		"inset", "inset-block", "inset-block-start", "inset-block-end",
		"inset-inline", "inset-inline-start", "inset-inline-end");

	private static final Pattern CODE_LANGUAGE = Pattern.compile("language-[A-Za-z0-9_+#.-]{0,40}");

	private static final Set<String> EDITOR_BLOCK_TYPES = Set.of("todo", "video");

	private RichTextSanitizer() {
	}

	public static String sanitize(String html) {
		if (html == null || html.isEmpty()) {
			return html;
		}
		Document clean = new Cleaner(SAFELIST).clean(Jsoup.parseBodyFragment(html, BASE_URI));
		// 不重新排版：换行和空格照原样输出，编辑器再打开时内容不变
		clean.outputSettings().prettyPrint(false);
		for (Element element : clean.body().getAllElements()) {
			tighten(element);
		}
		return clean.body().html();
	}

	/** 名单按属性名放行；值还需要再看一眼的几样在这里收紧 */
	private static void tighten(Element element) {
		if (element.hasAttr("style")) {
			String style = safeStyle(element.attr("style"));
			if (style.isEmpty()) {
				element.removeAttr("style");
			} else {
				element.attr("style", style);
			}
		}
		switch (element.normalName()) {
			case "a" -> {
				if (!"_blank".equals(element.attr("target"))) {
					element.removeAttr("target");
				}
			}
			case "code" -> {
				if (!CODE_LANGUAGE.matcher(element.attr("class")).matches()) {
					element.removeAttr("class");
				}
			}
			case "div" -> {
				if (!EDITOR_BLOCK_TYPES.contains(element.attr("data-w-e-type"))) {
					element.removeAttr("data-w-e-type");
					element.removeAttr("data-w-e-is-void");
				}
			}
			case "img" -> {
				String src = withoutControlAndSpace(element.attr("src"));
				if (startsWithIgnoreCase(src, "data:") && !startsWithIgnoreCase(src, "data:image/")) {
					element.removeAttr("src");
				}
			}
			default -> {
			}
		}
	}

	/**
	 * 行内样式默认保留，只去掉定位类的声明。带转义、注释、@ 规则或花括号的整段不要：正常的行内样式用不到，
	 * 而有了它们，浏览器读到的属性名就可能和这里按分号切出来的不一样。
	 */
	static String safeStyle(String style) {
		if (style.indexOf('\\') >= 0 || style.indexOf('@') >= 0 || style.indexOf('{') >= 0
			|| style.indexOf('}') >= 0 || style.contains("/*")) {
			return "";
		}
		StringBuilder out = new StringBuilder();
		for (String declaration : style.split(";")) {
			int colon = declaration.indexOf(':');
			if (colon <= 0) {
				continue;
			}
			String property = declaration.substring(0, colon).trim().toLowerCase(Locale.ROOT);
			String value = declaration.substring(colon + 1).trim();
			if (property.isEmpty() || value.isEmpty() || BLOCKED_STYLE_PROPERTIES.contains(property)) {
				continue;
			}
			if (out.length() > 0) {
				out.append(' ');
			}
			out.append(property).append(": ").append(value).append(';');
		}
		return out.toString();
	}

	private static boolean startsWithIgnoreCase(String value, String prefix) {
		return value.regionMatches(true, 0, prefix, 0, prefix.length());
	}

	/** 浏览器解析地址时会忽略其中的控制字符和首尾空白，看前缀之前先去掉 */
	private static String withoutControlAndSpace(String value) {
		StringBuilder out = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (c > ' ') {
				out.append(c);
			}
		}
		return out.toString();
	}
}
