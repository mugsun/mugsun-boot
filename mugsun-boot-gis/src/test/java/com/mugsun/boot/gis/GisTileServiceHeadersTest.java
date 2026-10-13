package com.mugsun.boot.gis;

import com.mugsun.boot.gis.engine.GisUpstreamHttp;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 瓦片响应：白名单图片原样回、其余换成空 PNG；一律带 nosniff。上游客户端不跟随重定向。
 */
class GisTileServiceHeadersTest {

	@Test
	void imageResponseCarriesNosniff() {
		byte[] png = Arrays.copyOf(new byte[] { (byte) 0x89, 'P', 'N', 'G' }, 32);
		ResponseEntity<byte[]> ok = GisTileService.imageOrEmpty(200, "image/png", png);
		assertThat(ok.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
		assertThat(ok.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
		assertThat(ok.getBody()).isSameAs(png);
	}

	@Test
	void svgAndErrorsBecomeEmptyPngWithNosniff() {
		byte[] svg = Arrays.copyOf("<svg onload=alert(1)>".getBytes(StandardCharsets.US_ASCII), 32);
		for (ResponseEntity<byte[]> r : new ResponseEntity[] {
			GisTileService.imageOrEmpty(200, "image/svg+xml", svg),
			GisTileService.imageOrEmpty(302, "image/png", svg),
			GisTileService.imageOrEmpty(200, "text/html", svg)
		}) {
			assertThat(r.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
			assertThat(r.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
			assertThat(r.getBody()).isNotSameAs(svg);
			assertThat(r.getBody()[1]).isEqualTo((byte) 'P');
		}
	}

	@Test
	void upstreamClientsNeverFollowRedirects() {
		assertThat(GisUpstreamHttp.followsRedirects()).isFalse();
	}
}
