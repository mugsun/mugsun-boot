package com.mugsun.boot;

import cn.idev.excel.FastExcel;
import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.common.excel.ImportFiles;
import com.mugsun.boot.system.excel.SysUserExcel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导入公共校验（UX-102/103/104）：空文件、扩展名与文件头不符、0 行，均回业务 400，不再 500 或「成功 0 条」。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportValidationApiTest extends AbstractIntegrationTest {

	private String adminToken;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
	}

	@Test
	void userImportEmptyFileIs400() {
		JsonNode r = upload("/system/user/import", new byte[0], "users.xlsx");
		assertThat(r.path("code").asInt()).isEqualTo(400);
		assertThat(r.path("msg").asText()).isEqualTo(ImportFiles.MSG_EMPTY);
	}

	@Test
	void userImportTextRenamedToXlsxIsBadFormat() {
		JsonNode r = upload("/system/user/import", "username,nickname\nalice,A\n".getBytes(StandardCharsets.UTF_8), "users.xlsx");
		assertThat(r.path("code").asInt()).isEqualTo(400);
		assertThat(r.path("msg").asText()).isEqualTo(ImportFiles.MSG_BAD_FORMAT);
	}

	@Test
	void userImportWrongExtensionIsBadFormat() {
		JsonNode r = upload("/system/user/import", xlsx(List.of()), "users.txt");
		assertThat(r.path("code").asInt()).isEqualTo(400);
		assertThat(r.path("msg").asText()).isEqualTo(ImportFiles.MSG_BAD_FORMAT);
	}

	@Test
	void userImportHeaderOnlyIsNotSuccess() {
		JsonNode r = upload("/system/user/import", xlsx(List.of()), "users.xlsx");
		assertThat(r.path("code").asInt()).isEqualTo(400);
		assertThat(r.path("success").asBoolean()).isFalse();
		assertThat(r.path("msg").asText()).contains("没有用户");
	}

	@Test
	void userImportAllRowsFailedIsNotSuccessButKeepsDetail() {
		SysUserExcel bad = new SysUserExcel();
		bad.setUsername("imp_bad_" + System.nanoTime());
		bad.setPhone("123");
		JsonNode r = upload("/system/user/import", xlsx(List.of(bad)), "users.xlsx");
		assertThat(r.path("code").asInt()).isEqualTo(400);
		assertThat(r.path("msg").asText()).contains("一条都没有导入");
		assertThat(r.path("data").path("failList").size()).isEqualTo(1);
	}

	@Test
	void regionImportEmptyFileSaysEmptyNotPleaseChoose() throws Exception {
		JsonNode empty = upload("/system/region/import", new byte[0], "region.xlsx");
		assertThat(empty.path("code").asInt()).isEqualTo(400);
		assertThat(empty.path("msg").asText()).isEqualTo(ImportFiles.MSG_EMPTY);
		Thread.sleep(3200); // 避开 @RepeatSubmit 判重窗
		JsonNode bad = upload("/system/region/import", "not excel".getBytes(StandardCharsets.UTF_8), "region.xlsx");
		assertThat(bad.path("code").asInt()).isEqualTo(400);
		assertThat(bad.path("msg").asText()).isEqualTo(ImportFiles.MSG_BAD_FORMAT);
	}

	private byte[] xlsx(List<SysUserExcel> rows) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		FastExcel.write(out, SysUserExcel.class).sheet("用户导入").doWrite(rows);
		return out.toByteArray();
	}

	private JsonNode upload(String url, byte[] content, String filename) {
		HttpHeaders headers = authHeaders(adminToken);
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return filename;
			}
		});
		ResponseEntity<String> resp = rest.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
		assertThat(resp.getStatusCode().value()).as("不应是 500：" + resp.getBody()).isLessThan(500);
		return readBody(resp);
	}
}
