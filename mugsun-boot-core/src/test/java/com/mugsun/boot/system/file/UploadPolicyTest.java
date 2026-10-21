package com.mugsun.boot.system.file;

import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadPolicyTest {

	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};

	@Test
	void acceptsWhitelistedFileWithMatchingMagic() {
		assertThat(UploadPolicy.validate(new MockMultipartFile("file", "a.PNG", "image/png", PNG))).isEqualTo("png");
		assertThat(UploadPolicy.validate(new MockMultipartFile("file", "a.pdf", null, "%PDF-1.7\n".getBytes()))).isEqualTo("pdf");
		assertThat(UploadPolicy.validate(new MockMultipartFile("file", "a.csv", null, "a,b\n1,2".getBytes()))).isEqualTo("csv");
	}

	@Test
	void rejectsScriptableTypes() {
		for (String name : new String[]{"x.html", "x.htm", "x.svg", "x.js", "x.xml", "x.exe", "x.jsp", "noext"}) {
			assertThatThrownBy(() -> UploadPolicy.validate(new MockMultipartFile("file", name, null, "<a>".getBytes())))
				.as(name).isInstanceOf(ServiceException.class);
		}
	}

	@Test
	void rejectsRenamedFiles() {
		assertThatThrownBy(() -> UploadPolicy.validate(new MockMultipartFile("file", "x.png", "image/png", "GIF89a".getBytes())))
			.isInstanceOf(ServiceException.class);
		assertThatThrownBy(() -> UploadPolicy.validate(new MockMultipartFile("file", "x.txt", null,
			"\uFEFF  <html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))))
			.isInstanceOf(ServiceException.class);
	}

	@Test
	void rejectsOversize() {
		byte[] big = new byte[(int) UploadPolicy.MAX_BYTES + 1];
		System.arraycopy(PNG, 0, big, 0, PNG.length);
		assertThatThrownBy(() -> UploadPolicy.validate(new MockMultipartFile("file", "a.png", null, big)))
			.isInstanceOf(ServiceException.class);
	}

	@Test
	void storedNameIgnoresUserFilename() {
		String n = UploadPolicy.storedName("png");
		assertThat(n).matches("[0-9a-f]{32}\\.png");
	}

	@Test
	void activeContentTypes() {
		assertThat(UploadPolicy.isActiveContentType("text/html; charset=utf-8")).isTrue();
		assertThat(UploadPolicy.isActiveContentType("image/svg+xml")).isTrue();
		assertThat(UploadPolicy.isActiveContentType("application/javascript")).isTrue();
		assertThat(UploadPolicy.isActiveContentType("image/png")).isFalse();
		assertThat(UploadPolicy.isActiveContentType("text/plain")).isFalse();
	}
}
