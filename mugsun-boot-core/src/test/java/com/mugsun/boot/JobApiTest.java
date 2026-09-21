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
 * 定时任务回归（不依赖 PowerJob Server）：处理器注册表可读；未注册处理器保存被拒；未登录 401。
 * list 在无 7700 时应业务失败而非 500；save 未注册处理器被拒；未登录 401。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JobApiTest extends AbstractIntegrationTest {

	private String adminToken;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
	}

	@Test
	void unauthenticatedIsUnauthorized() {
		ResponseEntity<String> resp = get("/system/job/processors", null);
		assertThat(resp.getStatusCode().value()).isEqualTo(401);
	}

	@Test
	void processorsRegistryIsReadable() {
		JsonNode r = readBody(get("/system/job/processors", adminToken));
		assertThat(r.path("code").asInt()).as("processors：" + r.path("msg").asText()).isEqualTo(200);
		assertThat(r.path("data").isArray()).isTrue();
		assertThat(r.path("data").size()).as("应至少有一个 BasicProcessor Bean").isGreaterThan(0);
		JsonNode first = r.path("data").get(0);
		assertThat(first.path("value").asText()).isNotBlank();
		assertThat(first.path("label").asText()).isNotBlank();
	}

	@Test
	void listDoesNotReturnHttp500() {
		ResponseEntity<String> resp = get("/system/job/list", adminToken);
		assertThat(resp.getStatusCode().value()).as("连不上 Server 时也应走 R 信封而非 5xx").isLessThan(500);
		JsonNode r = readBody(resp);
		if (r.path("code").asInt() != 200) {
			assertThat(r.path("msg").asText()).contains("定时任务服务不可用");
		} else {
			assertThat(r.path("data").isArray()).as("Server 可用时返回任务数组").isTrue();
		}
	}

	@Test
	void saveRejectsUnregisteredProcessor() {
		Map<String, Object> body = new HashMap<>();
		body.put("jobName", "it-job-bad");
		body.put("jobDescription", "集成测试");
		body.put("processorInfo", "com.mugsun.boot.job.DoesNotExistProcessor");
		body.put("jobParams", "{}");
		body.put("timeExpressionType", "API");
		JsonNode r = readBody(post("/system/job/save", body, adminToken));
		assertThat(r.path("code").asInt()).as("未注册处理器应业务失败").isNotEqualTo(200);
		assertThat(r.path("msg").asText()).contains("处理器未注册");
	}
}
