package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 流程设计器回归：线性 design + 图形 design-graph（条件分支）→ 停用/启用 → 删除。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlowGraphApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private String adminToken;
	private long linearDefId;
	private long graphDefId;
	private final String linearCode = "it_lin_" + TS.substring(TS.length() - 8);
	private final String graphCode = "it_grp_" + TS.substring(TS.length() - 8);

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
	}

	@Test
	@Order(1)
	void designLinearApproval() {
		Map<String, Object> node = new HashMap<>();
		node.put("name", "一级审批");
		node.put("candidates", List.of("role:admin"));
		node.put("role", null);
		node.put("nodeRatio", "0");
		node.put("fieldPerms", null);

		Map<String, Object> body = new HashMap<>();
		body.put("flowCode", linearCode);
		body.put("flowName", "IT线性流程-" + TS);
		body.put("formKey", null);
		body.put("nodes", List.of(node));

		JsonNode r = readBody(post("/system/flow/design", body, adminToken));
		assertThat(r.path("code").asInt()).as("design：" + r.path("msg").asText()).isEqualTo(200);
		linearDefId = r.path("data").asLong();
		assertThat(linearDefId).isPositive();
	}

	@Test
	@Order(2)
	void designGraphWithCondition() {
		Map<String, Object> yesChild = new HashMap<>();
		yesChild.put("type", "approval");
		yesChild.put("name", "高金额审批");
		yesChild.put("candidates", List.of("role:admin"));
		yesChild.put("role", null);
		yesChild.put("nodeRatio", "0");
		yesChild.put("fieldPerms", null);
		yesChild.put("branches", null);
		yesChild.put("childNode", null);

		Map<String, Object> noChild = new HashMap<>();
		noChild.put("type", "approval");
		noChild.put("name", "普通审批");
		noChild.put("candidates", List.of("role:admin"));
		noChild.put("role", null);
		noChild.put("nodeRatio", "0");
		noChild.put("fieldPerms", null);
		noChild.put("branches", null);
		noChild.put("childNode", null);

		Map<String, Object> yesBranch = new HashMap<>();
		yesBranch.put("name", "高");
		yesBranch.put("conditions", List.of(Map.of("field", "amount", "op", "gt", "value", "1000")));
		yesBranch.put("logic", "and");
		yesBranch.put("childNode", yesChild);

		Map<String, Object> elseBranch = new HashMap<>();
		elseBranch.put("name", "否则");
		elseBranch.put("conditions", List.of());
		elseBranch.put("logic", "and");
		elseBranch.put("childNode", noChild);

		Map<String, Object> root = new HashMap<>();
		root.put("type", "condition");
		root.put("name", "金额网关");
		root.put("candidates", null);
		root.put("role", null);
		root.put("nodeRatio", null);
		root.put("fieldPerms", null);
		root.put("branches", List.of(yesBranch, elseBranch));
		root.put("childNode", null);

		Map<String, Object> body = new HashMap<>();
		body.put("flowCode", graphCode);
		body.put("flowName", "IT图形流程-" + TS);
		body.put("category", "it");
		body.put("formKey", null);
		body.put("root", root);

		JsonNode r = readBody(post("/system/flow/design-graph", body, adminToken));
		assertThat(r.path("code").asInt()).as("design-graph：" + r.path("msg").asText()).isEqualTo(200);
		graphDefId = r.path("data").asLong();
		assertThat(graphDefId).isPositive();
	}

	@Test
	@Order(3)
	void suspendAndActiveGraphDefinition() {
		assertThat(readBody(post("/system/flow/definition/suspend/" + graphDefId, Map.of(), adminToken))
			.path("code").asInt()).isEqualTo(200);
		assertThat(readBody(post("/system/flow/definition/active/" + graphDefId, Map.of(), adminToken))
			.path("code").asInt()).isEqualTo(200);
	}

	@Test
	@Order(4)
	void cleanupDefinitions() {
		assertThat(readBody(post("/system/flow/definition/remove",
			List.of(linearDefId, graphDefId), adminToken)).path("code").asInt()).isEqualTo(200);
	}
}
