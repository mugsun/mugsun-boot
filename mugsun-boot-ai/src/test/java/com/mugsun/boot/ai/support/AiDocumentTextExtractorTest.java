package com.mugsun.boot.ai.support;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AiDocumentTextExtractorTest {

	private final AiDocumentTextExtractor extractor = new AiDocumentTextExtractor();

	@Test
	void extractsPdfText() throws Exception {
		byte[] bytes = load("kb-fixtures/sample.pdf");
		String[] reason = new String[1];
		String text = extractor.extractOrNull(bytes, "sample.pdf", "application/pdf", reason);
		assertThat(reason[0]).isNull();
		assertThat(text).containsIgnoringCase("Mugsun");
	}

	@Test
	void extractsDocxText() throws Exception {
		byte[] bytes = load("kb-fixtures/sample.docx");
		String text = extractor.extractOrNull(bytes, "sample.docx",
			AiKbDocumentTypes.mimeOf("docx"), new String[1]);
		assertThat(text).contains("Mugsun KB Docx Body");
	}

	@Test
	void extractsPptxText() throws Exception {
		byte[] bytes = load("kb-fixtures/sample.pptx");
		String text = extractor.extractOrNull(bytes, "sample.pptx",
			AiKbDocumentTypes.mimeOf("pptx"), new String[1]);
		assertThat(text).contains("Mugsun KB Pptx Body");
	}

	@Test
	void emptyPdfFailsWithReason() throws Exception {
		byte[] bytes = load("kb-fixtures/empty.pdf");
		String[] reason = new String[1];
		String text = extractor.extractOrNull(bytes, "empty.pdf", "application/pdf", reason);
		assertThat(text).isNull();
		assertThat(reason[0]).isNotBlank();
		assertThat(reason[0]).containsAnyOf("正文", "空", "OCR", "解析");
	}

	@Test
	void emptyBytesFail() {
		String[] reason = new String[1];
		assertThat(extractor.extractOrNull(new byte[0], "a.pdf", "application/pdf", reason)).isNull();
		assertThat(reason[0]).contains("空");
	}

	/**
	 * Tika 解析网页和源代码（比如文档里内嵌的附件）用的是 jsoup。整包只有一份 jsoup，版本由根 pom 统一指定，
	 * 和 Tika 自己声明的不一样；换版本后这两条路要照常抽得出正文。
	 */
	@Test
	void htmlIsParsedWithTheManagedJsoup() throws Exception {
		String html = "<html><head><title>标题</title><style>p{color:red}</style></head><body>"
			+ "<h1>Mugsun KB Html Body</h1><p>库存 &lt; 10 且销量 &gt; 5</p>"
			+ "<script>var hidden = 1;</script>"
			+ "<table><tr><td>单元格甲</td><td>单元格乙</td></tr></table></body></html>";
		String text = extractor.extract(html.getBytes(StandardCharsets.UTF_8), "embedded.html", "text/html");
		assertThat(text).contains("Mugsun KB Html Body", "库存 < 10 且销量 > 5", "单元格甲", "单元格乙");
		assertThat(text).doesNotContain("hidden").doesNotContain("color:red");
	}

	@Test
	void sourceCodeIsParsedWithTheManagedJsoup() throws Exception {
		String code = "public class Demo {\n\tint mugsunKbCodeBody = 1 < 2 ? 3 : 4;\n}\n";
		String text = extractor.extract(code.getBytes(StandardCharsets.UTF_8), "Demo.java", "text/x-java-source");
		assertThat(text).contains("Demo", "mugsunKbCodeBody", "<");
	}

	private static byte[] load(String path) throws Exception {
		try (InputStream in = AiDocumentTextExtractorTest.class.getClassLoader().getResourceAsStream(path)) {
			assertThat(in).as(path).isNotNull();
			return in.readAllBytes();
		}
	}
}
