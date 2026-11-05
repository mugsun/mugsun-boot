package com.mugsun.boot.ai.support;

import com.mugsun.boot.system.file.FileStorageTikaConfig;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.tika.Tika;
import org.apache.tika.mime.MimeTypes;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 回归：parsers-standard 在 classpath 时默认 {@code new Tika()} 探测 gzip 会炸；
 * x-file-storage 必须用 MimeTypes-only（见 {@link FileStorageTikaConfig}）。
 */
class TikaParsersDoNotBreakMimeDetectTest {

	@Test
	void defaultTikaDetectGzipThrowsWithParsersOnClasspath() throws Exception {
		byte[] gz = gzip("{\"k\":1}");
		assertThatThrownBy(() -> new Tika().detect(gz))
			.isInstanceOf(ArchiveException.class)
			.hasMessageContaining("No Archiver found");
	}

	@Test
	void mimeTypesOnlyAndFileStorageFactorySurviveGzip() throws Exception {
		byte[] gz = gzip("{\"k\":1}");
		assertThat(new Tika(MimeTypes.getDefaultMimeTypes()).detect(gz)).isEqualTo("application/gzip");
		assertThat(new FileStorageTikaConfig.MimeTypesOnlyTikaFactory().getTika().detect(gz))
			.isEqualTo("application/gzip");
	}

	private static byte[] gzip(String body) throws Exception {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
			gos.write(body.getBytes());
		}
		return bos.toByteArray();
	}
}
