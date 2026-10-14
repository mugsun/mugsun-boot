package com.mugsun.boot.gis;

import com.mugsun.boot.gis.engine.GisUpstreamHttp;
import com.mugsun.boot.gis.tile.GisTileFailure;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 瓦片响应：白名单图片原样回；其余给出失败原因（不再回 200 透明图）；一律带 nosniff。上游客户端不跟随重定向。
 */
class GisTileServiceHeadersTest {

	@Test
	void imageResponseCarriesNosniff() {
		byte[] png = Arrays.copyOf(new byte[] { (byte) 0x89, 'P', 'N', 'G' }, 32);
		assertThat(GisTileService.classify(200, "image/png", png)).isNull();
		ResponseEntity<byte[]> ok = GisTileService.image("image/png", png);
		assertThat(ok.getStatusCode().value()).isEqualTo(200);
		assertThat(ok.getHeaders().getContentType()).isEqualTo(MediaType.IMAGE_PNG);
		assertThat(ok.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
		assertThat(ok.getBody()).isSameAs(png);
	}

	@Test
	void svgHtmlAndUpstreamErrorsAreClassified() {
		byte[] svg = Arrays.copyOf("<svg onload=alert(1)>".getBytes(StandardCharsets.US_ASCII), 32);
		assertThat(GisTileService.classify(200, "image/svg+xml", svg)).isEqualTo(GisTileFailure.BAD_CONTENT_TYPE);
		assertThat(GisTileService.classify(200, "text/html", svg)).isEqualTo(GisTileFailure.BAD_CONTENT_TYPE);
		assertThat(GisTileService.classify(302, "image/png", svg)).isEqualTo(GisTileFailure.UPSTREAM_STATUS);
		assertThat(GisTileService.classify(500, "image/png", svg)).isEqualTo(GisTileFailure.UPSTREAM_STATUS);
	}

	@Test
	void failureResponseHasStatusReasonHeaderNoStoreAndNoBody() {
		for (GisTileFailure f : GisTileFailure.values()) {
			ResponseEntity<byte[]> r = GisTileService.failure(f);
			assertThat(r.getStatusCode().value()).isEqualTo(f.status()).isNotEqualTo(200);
			assertThat(r.getHeaders().getFirst(GisTileFailure.HEADER)).isEqualTo(f.code());
			assertThat(r.getHeaders().getCacheControl()).isEqualTo("no-store");
			assertThat(r.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
			assertThat(r.getBody()).isNull();
		}
	}

	@Test
	void upstreamClientsNeverFollowRedirects() {
		assertThat(GisUpstreamHttp.followsRedirects()).isFalse();
	}
}
