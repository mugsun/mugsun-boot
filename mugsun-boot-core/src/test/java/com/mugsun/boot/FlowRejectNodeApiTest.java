package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退回指定节点只能退到本实例走过、且在当前节点上游的审批节点：结束节点、开始节点、还没走到的后续节点、
 * 不存在的编码一律拒绝；退回一次之后，之前走过的后续节点也不能借退回跳过去。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlowRejectNodeApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final String REJECTED = "只能退回到前面已经办过的审批节点";
	private final String flowCode = "it_rej_" + TS.substring(TS.length() - 8);
	private String adminToken;
	private long definitionId;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		List<Map<String, Object>> nodes = new ArrayList<>();
		for (String name : List.of("一级审批", "二级审批", "三级审批")) {
			Map<String, Object> node = new HashMap<>();
			node.put("name", name);
			node.put("candidates", List.of("role:admin"));
			node.put("role", null);
			node.put("nodeRatio", "0");
			node.put("fieldPerms", null);
			nodes.add(node);
		}
		Map<String, Object> body = new HashMap<>();
		body.put("flowCode", flowCode);
		body.put("flowName", "IT退回节点-" + TS);
		body.put("formKey", null);
		body.put("nodes", nodes);
		JsonNode r = readBody(post("/system/flow/design", body, adminToken));
		assertThat(r.path("code").asInt()).as("design：" + r.path("msg").asText()).isEqualTo(200);
		definitionId = r.path("data").asLong();
	}

	@AfterAll
	void suspendDefinition() {
		post("/system/flow/definition/suspend/" + definitionId, Map.of(), adminToken);
	}

	@Test
	void firstApproverCannotJumpToEndOrAhead() {
		long ins = start("a");
		JsonNode task = todo(ins);
		assertThat(task.path("nodeCode").asText()).isEqualTo("approve1");
		long taskId = task.path("taskId").asLong();
		assertThat(backNodes(ins)).isEmpty();

		for (String target : List.of("end", "approve2", "approve3", "start", "no_such_node")) {
			assertRejected(rejectNode(taskId, target), target);
		}
		assertThat(todo(ins).path("taskId").asLong()).as("任务仍停在一级审批").isEqualTo(taskId);
		assertThat(flowStatus(ins)).as("实例没有被办结").isNotEqualTo("8");
	}

	@Test
	void returnsOnlyToVisitedUpstreamNodes() {
		long ins = start("b");
		pass(todo(ins));
		pass(todo(ins));
		JsonNode third = todo(ins);
		assertThat(third.path("nodeCode").asText()).isEqualTo("approve3");
		assertThat(backNodes(ins)).containsExactly("approve1", "approve2");
		assertRejected(rejectNode(third.path("taskId").asLong(), "end"), "end");

		JsonNode back = rejectNode(third.path("taskId").asLong(), "approve1");
		assertThat(back.path("code").asInt()).as("退回一级：" + back.path("msg").asText()).isEqualTo(200);
		JsonNode first = todo(ins);
		assertThat(first.path("nodeCode").asText()).isEqualTo("approve1");
		assertThat(backNodes(ins)).as("二、三级在一级下游，走过也不能作为退回目标").isEmpty();
		assertRejected(rejectNode(first.path("taskId").asLong(), "approve3"), "approve3");
		assertRejected(rejectNode(first.path("taskId").asLong(), "approve2"), "approve2");

		pass(first);
		JsonNode second = todo(ins);
		assertThat(second.path("nodeCode").asText()).isEqualTo("approve2");
		assertThat(backNodes(ins)).containsExactly("approve1");
		assertThat(flowStatus(ins)).isNotEqualTo("8");
	}

	private long start(String suffix) {
		JsonNode r = readBody(post("/system/flow/start-by/" + flowCode + "/it-rej-" + TS + "-" + suffix, Map.of(), adminToken));
		assertThat(r.path("code").asInt()).as("发起：" + r.path("msg").asText()).isEqualTo(200);
		return r.path("data").asLong();
	}

	private JsonNode todo(long instanceId) {
		for (JsonNode row : readBody(get("/system/flow/my-todo", adminToken)).path("data")) {
			if (row.path("instanceId").asLong() == instanceId) {
				return row;
			}
		}
		throw new AssertionError("待办里没有实例 " + instanceId);
	}

	private void pass(JsonNode task) {
		JsonNode r = readBody(post("/system/flow/task/handle/" + task.path("taskId").asLong(),
			Map.of("message", "同意"), adminToken));
		assertThat(r.path("code").asInt()).as("办理：" + r.path("msg").asText()).isEqualTo(200);
	}

	private JsonNode rejectNode(long taskId, String nodeCode) {
		return readBody(post("/system/flow/task/reject-node/" + taskId,
			Map.of("nodeCode", nodeCode, "message", "退回"), adminToken));
	}

	private List<String> backNodes(long instanceId) {
		JsonNode r = readBody(get("/system/flow/back-nodes?instanceId=" + instanceId, adminToken));
		assertThat(r.path("code").asInt()).as("back-nodes：" + r.path("msg").asText()).isEqualTo(200);
		List<String> codes = new ArrayList<>();
		r.path("data").forEach(n -> codes.add(n.path("nodeCode").asText()));
		return codes;
	}

	private String flowStatus(long instanceId) {
		return jdbc.queryForObject("SELECT flow_status FROM flow_instance WHERE id = ?", String.class, instanceId);
	}

	private static void assertRejected(JsonNode r, String target) {
		assertThat(r.path("code").asInt()).as("退回到 " + target + " 应被拒").isNotEqualTo(200);
		assertThat(r.path("msg").asText()).as("退回到 " + target).contains(REJECTED);
	}
}
