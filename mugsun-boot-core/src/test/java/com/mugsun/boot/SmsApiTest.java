package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 短信配置回归：分页/提交/启用互斥/删除；未登录 401。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SmsApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private String adminToken;
	private long smsId;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
	}

	@Test
	void unauthenticatedIsUnauthorized() {
		ResponseEntity<String> resp = get("/system/sms/page?pageNum=1&pageSize=10", null);
		assertThat(resp.getStatusCode().value()).isEqualTo(401);
	}

	@Test
	void submitEnableAndRemove() {
		Map<String, Object> body = new HashMap<>();
		body.put("name", "IT短信-" + TS);
		body.put("smsCode", "it_sms_" + TS);
		body.put("category", "ali");
		body.put("accessKey", "ak-it");
		body.put("secretKey", "sk-it");
		body.put("signature", "Mugsun");
		body.put("templateId", "SMS_IT");
		body.put("status", 0);
		body.put("remark", "集成测试");
		JsonNode submit = readBody(post("/system/sms/submit", body, adminToken));
		assertThat(submit.path("code").asInt()).as("提交：" + submit.path("msg").asText()).isEqualTo(200);

		JsonNode page = readBody(get("/system/sms/page?pageNum=1&pageSize=50", adminToken));
		assertThat(page.path("code").asInt()).isEqualTo(200);
		smsId = -1;
		for (JsonNode row : page.path("data").path("records")) {
			if (("IT短信-" + TS).equals(row.path("name").asText())) {
				smsId = row.path("id").asLong();
				break;
			}
		}
		assertThat(smsId).isPositive();

		JsonNode enable = readBody(post("/system/sms/enable/" + smsId, Map.of(), adminToken));
		assertThat(enable.path("code").asInt()).isEqualTo(200);
		JsonNode detail = readBody(get("/system/sms/detail?id=" + smsId, adminToken));
		assertThat(detail.path("data").path("status").asInt()).isEqualTo(1);

		JsonNode remove = readBody(post("/system/sms/remove", List.of(smsId), adminToken));
		assertThat(remove.path("code").asInt()).isEqualTo(200);
	}
}
