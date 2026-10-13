package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地存储直出（dev profile enable-access=true）访问控制：私有附件裸 url 匿名 404，
 * 授权下载返回短时签名 url 可取；签名篡改/挪用 404；公开附件仍匿名可取。
 */
class LocalFileAccessApiTest extends AbstractIntegrationTest {

	private JsonNode upload(String token, String access, String content) {
		return upload(token, access, content, "secret.txt");
	}

	private JsonNode upload(String token, String access, String content, String name) {
		HttpHeaders headers = authHeaders(token);
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
			@Override
			public String getFilename() {
				return name;
			}
		});
		body.add("access", access);
		ResponseEntity<String> resp = rest.exchange("/system/file/upload", HttpMethod.POST,
			new HttpEntity<>(body, headers), String.class);
		JsonNode json = readBody(resp);
		assertThat(json.path("code").asInt()).as("上传应成功：" + json).isEqualTo(200);
		return json.path("data");
	}

	private String rawPath(JsonNode attach) {
		return "/file/" + attach.path("basePath").asText("") + attach.path("path").asText()
			+ attach.path("filename").asText();
	}

	private ResponseEntity<String> anonymousGet(String pathAndQuery) {
		return rest.exchange(URI.create("http://127.0.0.1:" + port + pathAndQuery), HttpMethod.GET,
			HttpEntity.EMPTY, String.class);
	}

	@Test
	void privateLocalFileNeedsSignedUrl() {
		String token = loginAdmin();
		JsonNode attach = upload(token, "private", "top-secret");
		String raw = rawPath(attach);

		assertThat(anonymousGet(raw).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		JsonNode signed = readBody(get("/system/file/download/" + attach.path("id").asText(), token));
		assertThat(signed.path("code").asInt()).isEqualTo(200);
		URI url = URI.create(signed.path("data").asText());
		assertThat(url.getPath()).isEqualTo(raw);
		assertThat(url.getQuery()).contains("e=").contains("s=");

		ResponseEntity<String> ok = anonymousGet(url.getPath() + "?" + url.getRawQuery());
		assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(ok.getBody()).isEqualTo("top-secret");

		// 篡改签名 → 404
		assertThat(anonymousGet(url.getPath() + "?" + url.getRawQuery().replaceAll("s=.", "s=Z")).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		// 签名挪用到另一个私有文件 → 404
		JsonNode other = upload(token, "private", "other");
		assertThat(anonymousGet(rawPath(other) + "?" + url.getRawQuery()).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void publicLocalFileStaysAnonymous() {
		String token = loginAdmin();
		JsonNode attach = upload(token, "public", "hello");
		assertThat(attach.path("access").asText()).isEqualTo("public");
		ResponseEntity<String> resp = anonymousGet(rawPath(attach));
		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(resp.getBody()).isEqualTo("hello");
	}

	@Test
	void privateHtmlIsServedAsDownloadWithNosniff() {
		String token = loginAdmin();
		JsonNode attach = upload(token, "private", "<script>alert(1)</script>", "x.html");
		JsonNode signed = readBody(get("/system/file/download/" + attach.path("id").asText(), token));
		URI url = URI.create(signed.path("data").asText());
		ResponseEntity<String> resp = anonymousGet(url.getPath() + "?" + url.getRawQuery());
		assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(resp.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
		assertThat(resp.getHeaders().getFirst("Content-Disposition")).startsWith("attachment");
		assertThat(resp.getHeaders().getFirst("Content-Security-Policy")).isEqualTo("sandbox");
	}
}
