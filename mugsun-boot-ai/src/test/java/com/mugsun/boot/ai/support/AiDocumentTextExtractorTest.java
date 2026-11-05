package com.mugsun.boot.ai.support;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

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

	private static byte[] load(String path) throws Exception {
		try (InputStream in = AiDocumentTextExtractorTest.class.getClassLoader().getResourceAsStream(path)) {
			assertThat(in).as(path).isNotNull();
			return in.readAllBytes();
		}
	}
}
