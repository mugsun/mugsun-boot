package com.mugsun.boot.ai.support;

import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiKbDocumentTypesTest {

	@Test
	void acceptsWhitelistExt() {
		assertThat(AiKbDocumentTypes.requireExt("a.PDF")).isEqualTo("pdf");
		assertThat(AiKbDocumentTypes.requireExt("x.Docx")).isEqualTo("docx");
		assertThat(AiKbDocumentTypes.requireExt("/tmp/y.PPTX")).isEqualTo("pptx");
	}

	@Test
	void rejectsOtherExt() {
		assertThatThrownBy(() -> AiKbDocumentTypes.requireExt("a.txt"))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("PDF");
		assertThatThrownBy(() -> AiKbDocumentTypes.requireExt("a.doc"))
			.isInstanceOf(ServiceException.class);
	}

	@Test
	void rejectsFakePdfMagic() {
		byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
		assertThatThrownBy(() -> AiKbDocumentTypes.validate("x.pdf", "application/pdf", png, png.length))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("扩展名");
	}

	@Test
	void acceptsPdfMagic() {
		byte[] pdf = "%PDF-1.7\n".getBytes();
		assertThat(AiKbDocumentTypes.validate("a.pdf", "application/pdf", pdf, pdf.length)).isEqualTo("pdf");
	}

	@Test
	void rejectsBadMime() {
		byte[] pdf = "%PDF-1.7\n".getBytes();
		assertThatThrownBy(() -> AiKbDocumentTypes.validate("a.pdf", "text/html", pdf, pdf.length))
			.isInstanceOf(ServiceException.class)
			.hasMessageContaining("类型");
	}
}
