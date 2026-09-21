package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工作流回归：部署请假流程 → 发起 → 待办办理 → 历史/进度；未登录 401。
 * 候选 permissionFlag=admin（与 leave.json 一致），平台超管角色码命中即可办。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlowApiTest extends AbstractIntegrationTest {

	private String adminToken;
	private long definitionId;
	private long instanceId;
	private long taskId;
	private final String businessId = "it-leave-" + System.currentTimeMillis();

	@BeforeAll
	void login() {
		adminToken = loginAdmin();
	}

	@Test
	@Order(1)
	void unauthenticatedIsUnauthorized() {
		ResponseEntity<String> resp = get("/system/flow/definitions", null);
		assertThat(resp.getStatusCode().value()).isEqualTo(401);
	}

	@Test
	@Order(2)
	void deployLeaveDefinition() {
		JsonNode r = readBody(post("/system/flow/deploy", Map.of(), adminToken));
		assertThat(r.path("code").asInt()).as("部署：" + r.path("msg").asText()).isEqualTo(200);
		definitionId = r.path("data").asLong();
		assertThat(definitionId).isPositive();

		JsonNode list = readBody(get("/system/flow/definitions", adminToken));
		assertThat(list.path("code").asInt()).isEqualTo(200);
		boolean found = false;
		for (JsonNode row : list.path("data")) {
			if (row.path("id").asLong() == definitionId
				|| "leave".equals(row.path("flowCode").asText())
				|| "leave".equals(row.path("flow_code").asText())) {
				found = true;
				break;
			}
		}
		assertThat(found).as("定义列表应含 leave").isTrue();
	}

	@Test
	@Order(3)
	void startAndAppearInTodo() {
		JsonNode start = readBody(post("/system/flow/start/" + businessId, Map.of(), adminToken));
		assertThat(start.path("code").asInt()).as("发起：" + start.path("msg").asText()).isEqualTo(200);
		instanceId = start.path("data").asLong();
		assertThat(instanceId).isPositive();

		JsonNode todo = readBody(get("/system/flow/my-todo", adminToken));
		assertThat(todo.path("code").asInt()).isEqualTo(200);
		JsonNode matched = null;
		for (JsonNode row : todo.path("data")) {
			if (row.path("instanceId").asLong() == instanceId
				|| businessId.equals(row.path("businessId").asText())) {
				matched = row;
				break;
			}
		}
		assertThat(matched).as("发起后应出现在我的待办").isNotNull();
		taskId = matched.path("taskId").asLong();
		assertThat(taskId).isPositive();
	}

	@Test
	@Order(4)
	void handlePassCompletesAndHistoryVisible() {
		JsonNode handle = readBody(post("/system/flow/task/handle/" + taskId,
			Map.of("message", "集成测试通过"), adminToken));
		assertThat(handle.path("code").asInt()).as("办理：" + handle.path("msg").asText()).isEqualTo(200);

		JsonNode history = readBody(get("/system/flow/history?instanceId=" + instanceId, adminToken));
		assertThat(history.path("code").asInt()).isEqualTo(200);
		assertThat(history.path("data").isArray()).isTrue();
		assertThat(history.path("data").size()).as("历史应有流转记录").isGreaterThan(0);

		JsonNode progress = readBody(get("/system/flow/progress?instanceId=" + instanceId, adminToken));
		assertThat(progress.path("code").asInt()).isEqualTo(200);
		assertThat(progress.path("data").isArray()).isTrue();

		JsonNode started = readBody(get("/system/flow/my-started", adminToken));
		assertThat(started.path("code").asInt()).isEqualTo(200);
		boolean found = false;
		for (JsonNode row : started.path("data")) {
			if (row.path("id").asLong() == instanceId
				|| businessId.equals(row.path("businessId").asText())
				|| businessId.equals(row.path("business_id").asText())) {
				found = true;
				break;
			}
		}
		assertThat(found).as("办结后应在我发起列表可见").isTrue();
	}
}
