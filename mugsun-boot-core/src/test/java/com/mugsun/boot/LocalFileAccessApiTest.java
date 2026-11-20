package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.system.file.LocalFileUrlSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地存储直出（dev profile enable-access=true）访问控制：私有附件裸 url 匿名 404，
 * 授权下载返回短时签名 url 可取；签名篡改/挪用 404；公开附件仍匿名可取。
 * 直出关着时（prod 默认）登记 url 打不开：上传不返回它，取下载地址的接口说明改用按流下载。
 */
class LocalFileAccessApiTest extends AbstractIntegrationTest {

	@Autowired
	private LocalFileUrlSigner localFileUrlSigner;

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
		assertThat(anonymousGet(url.getPath() + "?" + tamperSign(url.getRawQuery())).getStatusCode())
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
		// 直出开着：上传返回的就是这个能打开的地址
		assertThat(URI.create(attach.path("url").asText()).getPath()).isEqualTo(rawPath(attach));
	}

	@Test
	void noDeadUrlWhenLocalDirectAccessIsOff() {
		String token = loginAdmin();
		String platform = upload(token, "private", "probe").path("platform").asText();
		// 测试环境的直出是开着的；这里临时把这个平台记成「没开直出」，用完改回去
		@SuppressWarnings("unchecked")
		Set<String> closed = (Set<String>) ReflectionTestUtils.getField(localFileUrlSigner, "closedPlatforms");
		assertThat(closed).isNotNull().doesNotContain(platform);
		closed.add(platform);
		try {
			// 公开上传：照常登记，但不给打不开的地址
			JsonNode attach = upload(token, "public", "hello");
			assertThat(attach.path("access").asText()).isEqualTo("public");
			assertThat(attach.path("url").asText("")).isEmpty();
			String id = attach.path("id").asText();

			// 取下载地址：说明改用按流下载，不返回一个 404 的地址（公开、私有都一样）
			JsonNode publicUrl = readBody(get("/system/file/download/" + id, token));
			assertThat(publicUrl.path("code").asInt()).isNotEqualTo(200);
			assertThat(publicUrl.path("msg").asText()).contains("/system/file/download-stream/" + id);
			JsonNode secret = upload(token, "private", "top-secret");
			JsonNode privateUrl = readBody(get("/system/file/download/" + secret.path("id").asText(), token));
			assertThat(privateUrl.path("code").asInt()).isNotEqualTo(200);
			assertThat(privateUrl.path("msg").asText()).contains("download-stream");

			// 按流下载不受影响
			ResponseEntity<String> stream = get("/system/file/download-stream/" + id, token);
			assertThat(stream.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(stream.getBody()).isEqualTo("hello");
		} finally {
			closed.remove(platform);
		}
	}

	@Test
	void scriptableUploadIsRejected() {
		// html / svg 等可脚本化类型在上传时就拒绝，不再落盘；存量文件仍由 LocalFileAccessFilter 强制 attachment + nosniff
		String token = loginAdmin();
		for (String name : new String[]{"x.html", "x.svg", "x.js"}) {
			HttpHeaders headers = authHeaders(token);
			headers.setContentType(MediaType.MULTIPART_FORM_DATA);
			MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
			body.add("file", new ByteArrayResource("<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)) {
				@Override
				public String getFilename() {
					return name;
				}
			});
			JsonNode json = readBody(rest.exchange("/system/file/upload", HttpMethod.POST,
				new HttpEntity<>(body, headers), String.class));
			assertThat(json.path("code").asInt()).as(name).isNotEqualTo(200);
		}
	}

	@Test
	void storedFilenameIsServerGenerated() {
		JsonNode attach = upload(loginAdmin(), "private", "hello", "../../evil name.txt");
		assertThat(attach.path("filename").asText()).matches("[0-9a-f]{32}\\.txt");
	}

	/** 把签名首字符换成一个一定不同的字符；原写法固定换成 Z，签名本身以 Z 开头时（约 1/64）等于没改 */
	private static String tamperSign(String query) {
		int at = query.startsWith("s=") ? 2 : query.indexOf("&s=") + 3;
		char c = query.charAt(at);
		return query.substring(0, at) + (c == 'A' ? 'B' : 'A') + query.substring(at + 1);
	}
}
