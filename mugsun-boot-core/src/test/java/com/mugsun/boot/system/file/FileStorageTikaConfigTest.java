package com.mugsun.boot.system.file;

import org.apache.tika.Tika;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class FileStorageTikaConfigTest {

	@Test
	void mimeTypesOnlyDetectsGzipWithoutThrowing() throws Exception {
		byte[] gz = gzip("{\"a\":1}");
		Tika tika = new FileStorageTikaConfig.MimeTypesOnlyTikaFactory().getTika();
		assertThat(tika.detect(gz)).isEqualTo("application/gzip");
		assertThat(tika.detect(gz, "evt.json.gz")).isEqualTo("application/gzip");
		assertThat(tika.detect(new byte[]{0, 1, 2, 3}, "x.bin")).isEqualTo("application/octet-stream");
		assertThat(tika.detect(new byte[0], "empty.json.gz")).isEqualTo("application/gzip");
	}

	private static byte[] gzip(String body) throws Exception {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (GZIPOutputStream gos = new GZIPOutputStream(bos)) {
			gos.write(body.getBytes());
		}
		return bos.toByteArray();
	}
}
