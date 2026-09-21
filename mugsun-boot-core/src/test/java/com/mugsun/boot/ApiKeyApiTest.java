package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API 密钥回归：生成明文仅一次、列表脱敏、停用后校验失败、删除。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiKeyApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private String adminToken;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
	}

	@Test
	void unauthenticatedIsUnauthorized() {
		ResponseEntity<String> resp = get("/system/api-key/page?pageNum=1&pageSize=10", null);
		assertThat(resp.getStatusCode().value()).isEqualTo(401);
	}

	@Test
	void generateMaskDisableVerifyAndRemove() {
		Map<String, Object> gen = new HashMap<>();
		gen.put("name", "IT密钥-" + TS);
		gen.put("scope", "read");
		gen.put("remark", "集成测试");
		JsonNode created = readBody(post("/system/api-key/generate", gen, adminToken));
		assertThat(created.path("code").asInt()).as("生成：" + created.path("msg").asText()).isEqualTo(200);
		long id = created.path("data").path("id").asLong();
		String ak = created.path("data").path("accessKey").asText();
		String sk = created.path("data").path("secretKey").asText();
		assertThat(id).isPositive();
		assertThat(ak).startsWith("AK");
		assertThat(sk).hasSize(32);

		JsonNode page = readBody(get("/system/api-key/page?pageNum=1&pageSize=50", adminToken));
		assertThat(page.path("code").asInt()).isEqualTo(200);
		String listedSk = null;
		for (JsonNode row : page.path("data").path("records")) {
			if (row.path("id").asLong() == id) {
				listedSk = row.path("secretKey").asText();
				break;
			}
		}
		assertThat(listedSk).isNotBlank();
		assertThat(listedSk).contains("******");
		assertThat(listedSk).isNotEqualTo(sk);

		JsonNode ok = readBody(post("/system/api-key/verify",
			Map.of("accessKey", ak, "secretKey", sk), adminToken));
		assertThat(ok.path("code").asInt()).isEqualTo(200);
		assertThat(ok.path("data").path("valid").asBoolean()).isTrue();

		assertThat(readBody(post("/system/api-key/disable/" + id, Map.of(), adminToken)).path("code").asInt())
			.isEqualTo(200);
		JsonNode disabled = readBody(post("/system/api-key/verify",
			Map.of("accessKey", ak, "secretKey", sk), adminToken));
		assertThat(disabled.path("data").path("valid").asBoolean()).isFalse();

		assertThat(readBody(post("/system/api-key/remove/" + id, Map.of(), adminToken)).path("code").asInt())
			.isEqualTo(200);
	}
}
