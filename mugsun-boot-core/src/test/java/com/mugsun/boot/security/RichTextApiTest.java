package com.mugsun.boot.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求里的文字原样保存、富文本保存前净化（BOOT-117）：走真实接口，看存进去再读出来的内容。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RichTextApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());

	/** 一段正文：编辑器会写出来的排版，夹着不该留下的脚本、事件属性、javascript: 链接、iframe 和定位样式 */
	private static final String RICH = """
		<h2>盘点安排</h2>\
		<p style="text-align: center; position: fixed; top: 0;"><span style="color: rgb(225, 60, 57);">月底前</span>完成盘点</p>\
		<ul><li>华东仓</li><li>华南仓</li></ul>\
		<table style="width: auto;"><tbody>\
		<tr><th colSpan="1" rowSpan="1" width="auto">仓库</th></tr>\
		<tr><td colSpan="1" rowSpan="1" width="auto">华东仓</td></tr>\
		</tbody></table>\
		<pre><code class="language-sql">select name from stock where qty &lt; 10</code></pre>\
		<p><a href="https://example.com/stock" target="_blank">盘点说明</a><a href="javascript:alert(1)">点我</a></p>\
		<p><img src="/api/system/file/1.png" alt="示意图" onerror="alert(1)"></p>\
		<script>alert(1)</script><iframe src="https://evil.example"></iframe>\
		<p onclick="alert(1)">结尾</p>""";

	private String adminToken;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
	}

	@Test
	@DisplayName("公告：标题里的 < > & 和标签样的文字原样保存，拿这些字符能搜到；正文留排版、去脚本")
	void noticeKeepsTitleAsTypedAndSanitizesContent() {
		String title = "库存 < 10 且销量 > 5 <b>加粗</b> <script>alert(1)</script> A&B \"引号\" " + TS;
		Map<String, Object> notice = new HashMap<>();
		notice.put("title", title);
		notice.put("content", RICH);
		notice.put("category", "notice");
		notice.put("allVisible", 1);
		try {
			assertOk("保存公告", post("/system/notice/submit", notice, adminToken));

			// 搜索词里带 < > 和标签样的文字：原样拿去匹配，不会先被改写
			JsonNode page = readBody(get(
				"/system/notice/page?pageNum=1&pageSize=50&title=< 10 且销量 > 5 <b>加粗</b> <script>alert(1)</script>",
				adminToken));
			long id = idOfTitle(page.path("data").path("records"), title);
			assertThat(id).as("按带 < > 的词搜，应搜到标题原样保存的公告：" + page).isPositive();

			JsonNode detail = readBody(get("/system/notice/detail?id=" + id, adminToken)).path("data");
			assertThat(detail.path("title").asText()).isEqualTo(title);
			assertSanitized(detail.path("content").asText());
		} finally {
			removeAll("/system/notice/page?pageNum=1&pageSize=50&title=" + TS, "/system/notice/remove");
		}
	}

	@Test
	@DisplayName("帮助文档：标题原样保存，正文留排版、去脚本")
	void helpDocKeepsTitleAndSanitizesContent() {
		String catalogName = "富文本目录-" + TS;
		String title = "库存 < 10 怎么办 <" + TS + ">";
		long catalogId = -1;
		try {
			Map<String, Object> catalog = new HashMap<>();
			catalog.put("parentId", 0);
			catalog.put("name", catalogName);
			catalog.put("sort", 1);
			assertOk("建目录", post("/system/help/catalog/submit", catalog, adminToken));
			catalogId = findCatalogId(readBody(get("/system/help/catalog/tree", adminToken)).path("data"), catalogName);
			assertThat(catalogId).isPositive();

			Map<String, Object> doc = new HashMap<>();
			doc.put("catalogId", catalogId);
			doc.put("title", title);
			doc.put("content", RICH);
			doc.put("sort", 1);
			assertOk("保存文档", post("/system/help/doc/submit", doc, adminToken));

			JsonNode page = readBody(get("/system/help/doc/page?pageNum=1&pageSize=50&title=" + TS, adminToken));
			long id = idOfTitle(page.path("data").path("records"), title);
			assertThat(id).as("标题应原样保存：" + page).isPositive();
			assertSanitized(readBody(get("/system/help/doc/detail?id=" + id, adminToken)).path("data").path("content").asText());
		} finally {
			removeAll("/system/help/doc/page?pageNum=1&pageSize=50&title=" + TS, "/system/help/doc/remove");
			if (catalogId > 0) {
				post("/system/help/catalog/remove/" + catalogId, Map.of(), adminToken);
			}
		}
	}

	@Test
	@DisplayName("更新日志：标题原样保存，正文留排版、去脚本")
	void changelogKeepsTitleAndSanitizesContent() {
		String title = "修了库存 < 10 时不提醒的问题 <" + TS + ">";
		Map<String, Object> changelog = new HashMap<>();
		changelog.put("version", "v117.0." + TS.substring(TS.length() - 4));
		changelog.put("type", "fix");
		changelog.put("title", title);
		changelog.put("content", RICH);
		changelog.put("sort", 1);
		try {
			assertOk("保存更新日志", post("/system/changelog/submit", changelog, adminToken));

			JsonNode page = readBody(get("/system/changelog/page?pageNum=1&pageSize=50&name=" + TS, adminToken));
			long id = idOfTitle(page.path("data").path("records"), title);
			assertThat(id).as("标题应原样保存：" + page).isPositive();
			assertSanitized(readBody(get("/system/changelog/detail?id=" + id, adminToken)).path("data").path("content").asText());
		} finally {
			removeAll("/system/changelog/page?pageNum=1&pageSize=50&name=" + TS, "/system/changelog/remove");
		}
	}

	@Test
	@DisplayName("站内信：标题原样保存，正文留排版、去脚本")
	void messageKeepsTitleAndSanitizesContent() {
		long me = readBody(get("/auth/info", adminToken)).path("data").path("userId").asLong();
		assertThat(me).isPositive();
		String title = "库存 < 10 提醒 <" + TS + ">";
		Map<String, Object> message = new HashMap<>();
		message.put("title", title);
		message.put("content", RICH);
		message.put("type", "notice");
		message.put("recipientIds", List.of(me));
		String mine = "/system/message/my/page?pageNum=1&pageSize=50&name=" + TS;
		try {
			assertOk("发站内信", post("/system/message/send", message, adminToken));

			JsonNode page = readBody(get(mine, adminToken));
			JsonNode received = null;
			for (JsonNode row : page.path("data").path("records")) {
				if (title.equals(row.path("title").asText())) {
					received = row;
				}
			}
			assertThat(received).as("标题应原样保存：" + page).isNotNull();
			assertSanitized(received.path("content").asText());
		} finally {
			removeAll(mine, "/system/message/remove");
		}
	}

	@Test
	@DisplayName("请求体上限照旧：2MB 以内交给接口处理，超过回 413")
	void bodyLimitStillApplies() {
		int limit = 2 * 1024 * 1024;
		// 全是 ASCII，字符数就是字节数
		String head = "{\"title\":\"limit-" + TS + "\",\"category\":\"notice\",\"content\":\"";
		String tail = "\"}";
		String filler = "x".repeat(limit - head.length() - tail.length());

		ResponseEntity<String> atLimit = post("/system/notice/submit", head + filler + tail, adminToken);
		assertThat(atLimit.getStatusCode().value()).as("刚好 2MB 不拦").isNotEqualTo(413);
		assertThat(readBody(atLimit).path("msg").asText()).as("到了接口，由接口按字数上限拒绝").contains("2 万字");

		ResponseEntity<String> over = post("/system/notice/submit", head + filler + "x" + tail, adminToken);
		assertThat(over.getStatusCode().value()).as("超过 2MB").isEqualTo(413);
		assertThat(readBody(over).path("code").asInt()).isEqualTo(413);
	}

	/** 排版还在，脚本、事件属性、javascript: 地址、iframe、定位样式不在 */
	private static void assertSanitized(String stored) {
		Element body = Jsoup.parseBodyFragment(stored).body();
		assertThat(body.select("h2").text()).as(stored).isEqualTo("盘点安排");
		assertThat(body.selectFirst("p").attr("style")).as("定位类的声明去掉，其余留下").isEqualTo("text-align: center;");
		assertThat(body.selectFirst("span").attr("style")).isEqualTo("color: rgb(225, 60, 57);");
		assertThat(body.select("ul > li")).hasSize(2);
		assertThat(body.selectFirst("table").attr("style")).isEqualTo("width: auto;");
		assertThat(body.select("table th, table td")).hasSize(2)
			.allSatisfy(cell -> assertThat(cell.attr("colspan")).isEqualTo("1"));
		Element code = body.selectFirst("pre > code");
		assertThat(code.attr("class")).isEqualTo("language-sql");
		assertThat(code.text()).isEqualTo("select name from stock where qty < 10");

		Element link = body.select("a").get(0);
		assertThat(link.attr("href")).isEqualTo("https://example.com/stock");
		assertThat(link.attr("target")).isEqualTo("_blank");
		assertThat(link.attr("rel")).isEqualTo("noopener noreferrer");
		Element scriptLink = body.select("a").get(1);
		assertThat(scriptLink.hasAttr("href")).as("javascript: 地址去掉，文字留下").isFalse();
		assertThat(scriptLink.text()).isEqualTo("点我");
		assertThat(body.selectFirst("img").attr("src")).isEqualTo("/api/system/file/1.png");

		assertThat(body.select("script, iframe")).as(stored).isEmpty();
		for (Element element : body.getAllElements()) {
			for (Attribute attribute : element.attributes()) {
				assertThat(attribute.getKey()).as(stored).doesNotStartWith("on");
			}
		}
		assertThat(body.select("p").last().text()).isEqualTo("结尾");
	}

	private void assertOk(String step, ResponseEntity<String> response) {
		JsonNode body = readBody(response);
		assertThat(body.path("code").asInt()).as(step + "：" + body.path("msg").asText()).isEqualTo(200);
	}

	private static long idOfTitle(JsonNode rows, String title) {
		for (JsonNode row : rows) {
			if (title.equals(row.path("title").asText())) {
				return row.path("id").asLong();
			}
		}
		return -1;
	}

	private static long findCatalogId(JsonNode nodes, String name) {
		for (JsonNode node : nodes) {
			if (name.equals(node.path("name").asText())) {
				return node.path("id").asLong();
			}
			long child = findCatalogId(node.path("children"), name);
			if (child > 0) {
				return child;
			}
		}
		return -1;
	}

	/** 清掉本次测试建的数据：列表按本次的时间戳筛，筛到的都删 */
	private void removeAll(String pageUrl, String removeUrl) {
		List<Long> ids = new ArrayList<>();
		for (JsonNode row : readBody(get(pageUrl, adminToken)).path("data").path("records")) {
			ids.add(row.path("id").asLong());
		}
		if (!ids.isEmpty()) {
			post(removeUrl, ids, adminToken);
		}
	}
}
