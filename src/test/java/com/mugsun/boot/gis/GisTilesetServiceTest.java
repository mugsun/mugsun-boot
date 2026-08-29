package com.mugsun.boot.gis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内置三维切片下发：白名单、后缀限制、路径穿越拦截，以及随包切片本身可解析。
 */
class GisTilesetServiceTest {

	private final GisTilesetService service = new GisTilesetService();

	@Test
	@DisplayName("示例清单下发为 JSON，含 root 与子瓦片")
	void servesManifest() throws Exception {
		ResponseEntity<byte[]> resp = service.manifest(GisConstants.TILESET_DEMO_CITY);

		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
		JsonNode root = new ObjectMapper().readTree(new String(resp.getBody(), StandardCharsets.UTF_8));
		assertThat(root.path("asset").path("version").asText()).isEqualTo("1.0");
		assertThat(root.path("root").path("transform")).hasSize(16);
		assertThat(root.path("root").path("children")).hasSize(4);
		assertThat(root.path("root").path("children").get(0).path("content").path("uri").asText())
			.endsWith(".b3dm");
	}

	@Test
	@DisplayName("切片体下发为二进制且长缓存，b3dm 头部合法")
	void servesContent() {
		ResponseEntity<byte[]> resp = service.content(GisConstants.TILESET_DEMO_CITY, "nw.b3dm");

		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
		assertThat(resp.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL)).contains("immutable");
		byte[] body = resp.getBody();
		assertThat(body).isNotNull();
		assertThat(new String(body, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("b3dm");
		// b3dm 头里的 byteLength 必须与实际长度一致，否则 Cesium 解析中断
		assertThat(leInt(body, 8)).isEqualTo(body.length);
	}

	@Test
	@DisplayName("白名单外的切片 code 拒绝")
	void rejectsUnknownCode() {
		assertThatThrownBy(() -> service.manifest("../../application"))
			.isInstanceOf(ServiceException.class)
			.hasMessage(GisConstants.MSG_TILESET_MISSING);
		assertThatThrownBy(() -> service.content("other", "nw.b3dm"))
			.isInstanceOf(ServiceException.class)
			.hasMessage(GisConstants.MSG_TILESET_MISSING);
	}

	@Test
	@DisplayName("文件名带路径跳转或非切片后缀一律拒绝")
	void rejectsTraversalAndOtherTypes() {
		for (String bad : new String[] {
			"../application.yml", "..%2Fapplication.yml", "sub/nw.b3dm", "nw.b3dm\\..\\x",
			"application.yml", "nw.txt", "", "  "
		}) {
			assertThatThrownBy(() -> service.content(GisConstants.TILESET_DEMO_CITY, bad))
				.as("应拒绝 %s", bad)
				.isInstanceOf(ServiceException.class)
				.hasMessage(GisConstants.MSG_TILESET_MISSING);
		}
	}

	@Test
	@DisplayName("后缀合法但文件不存在返回 404，而不是抛异常")
	void missingFileIsNotFound() {
		ResponseEntity<byte[]> resp = service.content(GisConstants.TILESET_DEMO_CITY, "no-such.b3dm");

		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	private static int leInt(byte[] data, int offset) {
		return (data[offset] & 0xFF)
			| (data[offset + 1] & 0xFF) << 8
			| (data[offset + 2] & 0xFF) << 16
			| (data[offset + 3] & 0xFF) << 24;
	}
}
