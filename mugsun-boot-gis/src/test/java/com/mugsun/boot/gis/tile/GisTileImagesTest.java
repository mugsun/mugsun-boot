package com.mugsun.boot.gis.tile;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class GisTileImagesTest {

	static byte[] pad(byte[] head) {
		return Arrays.copyOf(head, 32);
	}

	static final byte[] PNG = pad(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A });
	static final byte[] JPEG = pad(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0 });
	static final byte[] GIF = pad("GIF89a".getBytes(StandardCharsets.US_ASCII));
	static final byte[] WEBP = pad("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.US_ASCII));
	static final byte[] SVG = pad("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>".getBytes(StandardCharsets.US_ASCII));

	@Test
	void acceptsWhitelistedTypesWithMatchingMagic() {
		assertThat(GisTileImages.accept("image/png", PNG)).isEqualTo(MediaType.IMAGE_PNG);
		assertThat(GisTileImages.accept("image/jpeg; charset=binary", JPEG)).isEqualTo(MediaType.IMAGE_JPEG);
		assertThat(GisTileImages.accept("image/jpg", JPEG)).isEqualTo(MediaType.IMAGE_JPEG);
		assertThat(GisTileImages.accept("IMAGE/GIF", GIF)).isEqualTo(MediaType.IMAGE_GIF);
		assertThat(GisTileImages.accept("image/webp", WEBP)).isEqualTo(GisTileImages.WEBP);
	}

	@Test
	void rejectsSvgHtmlAndMislabelledBodies() {
		assertThat(GisTileImages.accept("image/svg+xml", SVG)).isNull();
		assertThat(GisTileImages.accept("image/png", SVG)).as("声明 png 实为 svg").isNull();
		assertThat(GisTileImages.accept("text/html", PNG)).isNull();
		assertThat(GisTileImages.accept("image/bmp", PNG)).isNull();
		assertThat(GisTileImages.accept("image/jpeg", PNG)).isNull();
		assertThat(GisTileImages.accept(null, PNG)).isNull();
		assertThat(GisTileImages.accept("image/png", new byte[] { (byte) 0x89, 'P' })).isNull();
	}
}
