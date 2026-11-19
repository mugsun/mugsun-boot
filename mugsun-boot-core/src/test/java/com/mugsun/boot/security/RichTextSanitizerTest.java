package com.mugsun.boot.security;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 富文本净化（BOOT-117）：编辑器写出来的排版要留住，能执行的内容要去掉，文字里的字符不能丢。
 */
class RichTextSanitizerTest {

	/** 按前端编辑器（wangEditor 5）的输出格式写的一篇公告 */
	private static final String EDITOR_HTML = """
		<h2>四季度库存盘点安排</h2>\
		<p style="text-align: center;">请各仓库在 <span style="color: rgb(225, 60, 57); background-color: rgb(255, 251, 143);">11 月 30 日前</span>完成盘点，<strong>逾期不候</strong>。</p>\
		<ul><li>华东仓：11 月 25 日</li><li>华南仓：11 月 27 日</li></ul>\
		<ol><li>先盘点</li><li>再上报</li></ol>\
		<table style="width: auto;"><tbody>\
		<tr><th colSpan="1" rowSpan="1" width="auto">仓库</th><th colSpan="1" rowSpan="1" width="auto">盘点日</th></tr>\
		<tr><td colSpan="1" rowSpan="1" width="auto">华东仓</td><td colSpan="1" rowSpan="1" width="auto">11 月 25 日</td></tr>\
		</tbody></table>\
		<blockquote>库存不足的商品按下面的条件筛</blockquote>\
		<pre><code class="language-sql">select name from stock where qty &lt; 10 and sold &gt; 5</code></pre>\
		<p><a href="https://example.com/stock?a=1&amp;b=2" target="_blank">盘点说明</a> <a href="/help/stock">站内说明</a></p>\
		<p><img src="/api/system/file/1.png" alt="示意图" data-href="https://example.com/big.png" style="width: 30%;"/></p>\
		<div data-w-e-type="todo"><input type="checkbox" disabled checked>已通知仓库主管</div>\
		<div data-w-e-type="video" data-w-e-is-void>
		<video poster="https://example.com/p.png" controls="true" width="auto" height="auto"><source src="https://example.com/v.mp4" type="video/mp4"/></video>
		</div>\
		<hr/><p><s>作废</s> <u>下划线</u> <em>斜体</em> <sub>下标</sub><sup>上标</sup> <mark>标记</mark></p>\
		<p><br></p>""";

	@Test
	@DisplayName("编辑器写出的标题、对齐、颜色、列表、表格、引用、代码块、链接、图片、待办、视频都保留")
	void keepsEditorFormatting() {
		Element body = body(RichTextSanitizer.sanitize(EDITOR_HTML));

		assertThat(body.text()).as("文字一个不少").isEqualTo(body(EDITOR_HTML).text());
		assertThat(body.select("h2").text()).isEqualTo("四季度库存盘点安排");
		assertThat(body.selectFirst("p").attr("style")).isEqualTo("text-align: center;");
		assertThat(body.selectFirst("span").attr("style"))
			.isEqualTo("color: rgb(225, 60, 57); background-color: rgb(255, 251, 143);");
		assertThat(body.select("strong").text()).isEqualTo("逾期不候");
		assertThat(body.select("ul > li")).hasSize(2);
		assertThat(body.select("ol > li")).hasSize(2);

		assertThat(body.selectFirst("table").attr("style")).isEqualTo("width: auto;");
		assertThat(body.select("table > tbody > tr")).hasSize(2);
		assertThat(body.select("th, td")).hasSize(4).allSatisfy(cell -> {
			assertThat(cell.attr("colspan")).isEqualTo("1");
			assertThat(cell.attr("rowspan")).isEqualTo("1");
			assertThat(cell.attr("width")).isEqualTo("auto");
		});

		assertThat(body.select("blockquote").text()).isEqualTo("库存不足的商品按下面的条件筛");
		Element code = body.selectFirst("pre > code");
		assertThat(code.attr("class")).isEqualTo("language-sql");
		assertThat(code.text()).isEqualTo("select name from stock where qty < 10 and sold > 5");

		Element external = body.select("a").get(0);
		assertThat(external.attr("href")).isEqualTo("https://example.com/stock?a=1&b=2");
		assertThat(external.attr("target")).isEqualTo("_blank");
		assertThat(external.attr("rel")).isEqualTo("noopener noreferrer");
		assertThat(body.select("a").get(1).attr("href")).isEqualTo("/help/stock");

		Element img = body.selectFirst("img");
		assertThat(img.attr("src")).isEqualTo("/api/system/file/1.png");
		assertThat(img.attr("alt")).isEqualTo("示意图");
		assertThat(img.attr("data-href")).isEqualTo("https://example.com/big.png");
		assertThat(img.attr("style")).isEqualTo("width: 30%;");

		Element todo = body.selectFirst("div[data-w-e-type=todo] > input");
		assertThat(todo).as("待办的勾选框").isNotNull();
		assertThat(todo.attr("type")).isEqualTo("checkbox");
		assertThat(todo.hasAttr("disabled")).isTrue();
		assertThat(todo.hasAttr("checked")).isTrue();

		Element video = body.selectFirst("div[data-w-e-type=video][data-w-e-is-void] > video");
		assertThat(video).as("视频").isNotNull();
		assertThat(video.attr("poster")).isEqualTo("https://example.com/p.png");
		assertThat(video.attr("controls")).isEqualTo("true");
		assertThat(video.selectFirst("source").attr("src")).isEqualTo("https://example.com/v.mp4");
		assertThat(video.selectFirst("source").attr("type")).isEqualTo("video/mp4");

		assertThat(body.select("hr, s, u, em, sub, sup, mark")).hasSize(7);
		assertThat(body.select("p > br")).hasSize(1);
	}

	@Test
	@DisplayName("净化过的内容再净化一遍不变：再次编辑保存不会越存越少")
	void isIdempotent() {
		String once = RichTextSanitizer.sanitize(EDITOR_HTML);
		assertThat(RichTextSanitizer.sanitize(once)).isEqualTo(once);
	}

	@Test
	@DisplayName("文字里的 < > & 和不换行空格留着")
	void keepsSpecialCharactersInText() {
		String html = "<p>库存 &lt; 10 且销量 &gt; 5，A&amp;B&nbsp;公司</p>";
		assertThat(RichTextSanitizer.sanitize(html)).isEqualTo(html);

		// 没写成实体的大于号、小于号也不丢
		String loose = RichTextSanitizer.sanitize("库存 < 10 且销量 > 5");
		assertThat(body(loose).text()).isEqualTo("库存 < 10 且销量 > 5");
	}

	@Test
	@DisplayName("去掉危险内容后其余部分照旧")
	void keepsTheRestWhenDroppingDangerousParts() {
		assertThat(RichTextSanitizer.sanitize("<script>alert(1)</script><p onclick='alert(2)'>正文</p>"))
			.isEqualTo("<p>正文</p>");
		assertThat(RichTextSanitizer.sanitize("<p>看图<img src='x.png' onerror='alert(1)'></p>"))
			.isEqualTo("<p>看图<img src=\"x.png\"></p>");
		assertThat(RichTextSanitizer.sanitize("<a href='javascript:alert(1)'>点我</a>"))
			.isEqualTo("<a rel=\"noopener noreferrer\">点我</a>");
		assertThat(RichTextSanitizer.sanitize("<!-- 注释 --><custom-tag>文字</custom-tag>")).isEqualTo("文字");
		// 系统自己拼的告警站内信：段落 + 站内链接
		assertThat(RichTextSanitizer.sanitize("<p>错误数达到 12 次</p><p><a href='#/track/error'>查看错误列表</a></p>"))
			.isEqualTo("<p>错误数达到 12 次</p><p><a href=\"#/track/error\" rel=\"noopener noreferrer\">查看错误列表</a></p>");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"<script>alert(1)</script>",
		"<SCRIPT SRC=//evil.example/x.js></SCRIPT>",
		"<p onclick='alert(1)' onmouseover='alert(2)'>点我</p>",
		"<img src='x' onerror='alert(1)'>",
		"<img src=x onerror=alert(1)//>",
		"<a href='javascript:alert(1)'>点我</a>",
		"<a href='JaVaScRiPt:alert(1)'>点我</a>",
		"<a href=' javascript:alert(1)'>点我</a>",
		"<a href='java&#9;script:alert(1)'>点我</a>",
		"<a href='&#106;avascript:alert(1)'>点我</a>",
		"<a href='vbscript:msgbox(1)'>点我</a>",
		"<a href='data:text/html,<script>alert(1)</script>'>点我</a>",
		"<iframe src='https://evil.example'></iframe>",
		"<iframe srcdoc='<script>alert(1)</script>'></iframe>",
		"<form action='https://evil.example'><input type='text' name='p'><button formaction='https://evil.example'>提交</button></form>",
		"<svg onload='alert(1)'><circle r='1'/></svg>",
		"<svg><script>alert(1)</script></svg>",
		"<math><mtext><script>alert(1)</script></mtext></math>",
		"<style>body{display:none}</style>",
		"<object data='x'></object><embed src='x'>",
		"<base href='https://evil.example/'><meta http-equiv='refresh' content='0;url=https://evil.example'><link rel='stylesheet' href='https://evil.example/x.css'>",
		"<video src='x' onerror='alert(1)'></video>",
		"<details open ontoggle='alert(1)'>x</details>",
		"<img src='data:text/html,<script>alert(1)</script>'>",
		"<img src='&#9;data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg=='>",
		"<noscript><p title='</noscript><img src=x onerror=alert(1)>'>"
	})
	@DisplayName("脚本、事件属性、javascript: 链接、iframe、表单、svg、style 都留不下来")
	void removesExecutableContent(String payload) {
		String clean = RichTextSanitizer.sanitize("<p>前文</p>" + payload + "<p>后文</p>");
		assertInert(clean);
		assertThat(body(clean).select("p").eachText()).as("%s → %s", payload, clean).contains("前文", "后文");
	}

	@Test
	@DisplayName("写在文本框里的标签只当文字留下，不会变回标签")
	void rawTextStaysText() {
		String clean = RichTextSanitizer.sanitize("<textarea><script>alert(1)</script></textarea>");
		assertInert(clean);
		assertThat(body(clean).text()).isEqualTo("<script>alert(1)</script>");
	}

	@Test
	@DisplayName("行内样式默认保留，只去掉定位类的声明")
	void keepsInlineStylesExceptPositioning() {
		assertThat(RichTextSanitizer.safeStyle("width: 30%;height: 20%;")).isEqualTo("width: 30%; height: 20%;");
		assertThat(RichTextSanitizer.safeStyle("COLOR : red ; font-size:14px")).isEqualTo("color: red; font-size: 14px;");
		assertThat(RichTextSanitizer.safeStyle(
			"position: fixed; top: 0; left: 0; right: 0; bottom: 0; inset: 0; z-index: 9999; width: 100%; background: #fff"))
			.isEqualTo("width: 100%; background: #fff;");
		assertThat(RichTextSanitizer.safeStyle("POSITION : absolute")).isEmpty();
		assertThat(RichTextSanitizer.safeStyle("inset-inline-start: 0; margin-left: 2em")).isEqualTo("margin-left: 2em;");

		// 转义、注释、@ 规则、花括号：正常的行内样式用不到，整段不要
		assertThat(RichTextSanitizer.safeStyle("pos\\69tion: fixed; color: red")).isEmpty();
		assertThat(RichTextSanitizer.safeStyle("color: red; pos/**/ition: fixed")).isEmpty();
		assertThat(RichTextSanitizer.safeStyle("color: red } body { display: none")).isEmpty();
		assertThat(RichTextSanitizer.safeStyle("@import url(x.css)")).isEmpty();

		assertThat(RichTextSanitizer.sanitize("<p style='position: fixed; top: 0; color: red'>遮住页面</p>"))
			.isEqualTo("<p style=\"color: red;\">遮住页面</p>");
		assertThat(RichTextSanitizer.sanitize("<p style='position: fixed'>遮住页面</p>")).isEqualTo("<p>遮住页面</p>");
		// 嵌在里层的元素一样处理
		assertThat(RichTextSanitizer.sanitize("<div><p><span style='position: fixed; color: red'>里层</span></p></div>"))
			.isEqualTo("<div><p><span style=\"color: red;\">里层</span></p></div>");
	}

	@Test
	@DisplayName("class、id 不留；代码块语言、新窗口打开、编辑器块类型按值放行")
	void tightensAttributeValues() {
		assertThat(RichTextSanitizer.sanitize("<p class='fixed inset-0' id='app'>x</p>")).isEqualTo("<p>x</p>");
		assertThat(RichTextSanitizer.sanitize("<pre><code class='language-js'>a</code></pre>"))
			.isEqualTo("<pre><code class=\"language-js\">a</code></pre>");
		assertThat(RichTextSanitizer.sanitize("<code class='fixed inset-0'>a</code>")).isEqualTo("<code>a</code>");
		assertThat(RichTextSanitizer.sanitize("<div data-w-e-type='other' data-w-e-is-void>x</div>")).isEqualTo("<div>x</div>");

		Element sameWindow = body(RichTextSanitizer.sanitize("<a href='https://example.com' target='_self'>x</a>")).selectFirst("a");
		assertThat(sameWindow.hasAttr("target")).isFalse();
		Element newWindow = body(RichTextSanitizer.sanitize("<a href='https://example.com' target='_blank'>x</a>")).selectFirst("a");
		assertThat(newWindow.attr("target")).isEqualTo("_blank");
	}

	@Test
	@DisplayName("站内相对链接、页内锚点、电话和邮件链接保留原样")
	void keepsRelativeAndCommonLinks() {
		for (String href : List.of("/help/stock", "#/track/error", "help/stock.html", "https://example.com/a?b=1",
			"mailto:a@example.com", "tel:10086")) {
			Element a = body(RichTextSanitizer.sanitize("<a href='" + href + "'>x</a>")).selectFirst("a");
			assertThat(a.attr("href")).as(href).isEqualTo(href);
			assertThat(a.attr("rel")).isEqualTo("noopener noreferrer");
		}
	}

	@Test
	@DisplayName("图片：站内地址和 data:image 保留，其他 data: 去掉")
	void keepsImagesButNotOtherDataUrls() {
		String png = "data:image/png;base64,iVBORw0KGgo=";
		assertThat(body(RichTextSanitizer.sanitize("<img src='" + png + "'>")).selectFirst("img").attr("src")).isEqualTo(png);
		assertThat(body(RichTextSanitizer.sanitize("<img src='/api/system/file/1.png'>")).selectFirst("img").attr("src"))
			.isEqualTo("/api/system/file/1.png");
		assertThat(body(RichTextSanitizer.sanitize("<img src='data:text/html,x'>")).selectFirst("img").hasAttr("src")).isFalse();
	}

	@Test
	@DisplayName("空内容原样返回")
	void nullAndEmptyPassThrough() {
		assertThat(RichTextSanitizer.sanitize(null)).isNull();
		assertThat(RichTextSanitizer.sanitize("")).isEmpty();
	}

	/** 净化结果里没有能执行的东西：按解析后的节点和属性看，不按字符串猜 */
	private static void assertInert(String clean) {
		Element body = body(clean);
		assertThat(body.select("script, iframe, frame, form, button, svg, math, style, object, embed, base, meta, link, "
			+ "noscript, details, textarea, select"))
			.as(clean).isEmpty();
		for (Element el : body.getAllElements()) {
			for (Attribute attr : el.attributes()) {
				String key = attr.getKey().toLowerCase(Locale.ROOT);
				String value = attr.getValue().replaceAll("[\\x00-\\x20]", "").toLowerCase(Locale.ROOT);
				assertThat(key).as(clean).doesNotStartWith("on").isNotIn("srcdoc", "action", "formaction");
				assertThat(value).as(clean).doesNotStartWith("javascript:").doesNotStartWith("vbscript:")
					.doesNotStartWith("data:text");
			}
		}
	}

	private static Element body(String html) {
		return Jsoup.parseBodyFragment(html).body();
	}
}
