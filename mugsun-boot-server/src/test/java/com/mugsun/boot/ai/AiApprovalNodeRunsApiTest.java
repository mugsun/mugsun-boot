package com.mugsun.boot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BOOT-123：审批流程里的 AI 预审节点要真的运行绑定的 AI 应用。
 * 流程引擎的表不写租户，节点运行时按发起人所属租户算；以前取不到租户就判失败，应用一次也不运行，流程停在 AI 预审节点。
 * 应用里只有「改变量」节点，不调模型。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiApprovalNodeRunsApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final String TAIL = TS.substring(TS.length() - 8);
	private static final String AI_FIRST = "it_aipre_a_" + TAIL;
	private static final String AI_SECOND = "it_aipre_b_" + TAIL;
	private static final String DSL = "{\"nodes\":["
		+ "{\"id\":\"start\",\"type\":\"start\",\"data\":{\"type\":\"start\",\"label\":\"开始\"}},"
		+ "{\"id\":\"u1\",\"type\":\"updateVar\",\"data\":{\"type\":\"updateVar\",\"label\":\"预审结论\",\"operations\":["
		+ "{\"target\":\"ai_pass\",\"action\":\"set\",\"value\":\"true\"},"
		+ "{\"target\":\"ai_reason\",\"action\":\"set\",\"value\":\"材料齐全\"}]}},"
		+ "{\"id\":\"end\",\"type\":\"end\",\"data\":{\"type\":\"end\",\"label\":\"结束\"}}],"
		+ "\"edges\":[{\"id\":\"e1\",\"source\":\"start\",\"target\":\"u1\"},{\"id\":\"e2\",\"source\":\"u1\",\"target\":\"end\"}]}";

	private final ObjectMapper json = new ObjectMapper();
	private final List<Long> definitionIds = new ArrayList<>();
	private String adminToken;
	private long appId;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();

		Map<String, Object> app = new HashMap<>();
		app.put("name", "it-aipre-" + TS);
		app.put("appType", "workflow");
		app.put("description", "集成测试：审批流程里的 AI 预审");
		JsonNode created = readBody(post("/system/ai/app/submit", app, adminToken));
		assertThat(created.path("code").asInt()).as("建应用：" + created.path("msg").asText()).isEqualTo(200);
		appId = created.path("data").path("id").asLong();
		assertThat(appId).isPositive();
		JsonNode saved = readBody(post("/system/ai/app/dsl/save?id=" + appId, Map.of("dsl", DSL), adminToken));
		assertThat(saved.path("code").asInt()).as("存编排：" + saved.path("msg").asText()).isEqualTo(200);

		// 一条发起后马上进 AI 预审；另一条先过一道人工审批，AI 预审在办理请求里运行
		design(AI_FIRST, "IT 先预审再审批-" + TS, aiNode(approval("主管审批", null)));
		design(AI_SECOND, "IT 初审后预审-" + TS, approval("部门初审", aiNode(approval("主管审批", null))));
	}

	@AfterAll
	void cleanup() {
		for (String code : List.of(AI_FIRST, AI_SECOND)) {
			for (JsonNode row : readBody(get("/system/flow/running?flowCode=" + code, adminToken)).path("data")) {
				Map<String, Object> stop = new HashMap<>();
				stop.put("instanceId", row.path("instanceId").asText());
				stop.put("reason", "集成测试结束，清理");
				post("/system/flow/running/terminate", stop, adminToken);
			}
		}
		for (Long id : definitionIds) {
			post("/system/flow/definition/suspend/" + id, Map.of(), adminToken);
		}
		if (appId > 0) {
			post("/system/ai/app/remove?ids=" + appId, null, adminToken);
		}
	}

	@Test
	@DisplayName("发起时就进 AI 预审：应用运行一次，结论写进流程变量，流程走到下一个审批节点")
	void aiNodeRunsTheAppWhenTheFlowStarts() throws Exception {
		String biz = "it-aipre-" + TS + "-start";
		int runsBefore = successfulRuns();

		JsonNode started = readBody(post("/system/flow/start-by/" + AI_FIRST + "/" + biz, Map.of(), adminToken));

		assertThat(started.path("code").asInt()).as("发起：" + started.path("msg").asText()).isEqualTo(200);
		assertThat(successfulRuns() - runsBefore).as("AI 应用运行成功的次数").isEqualTo(1);
		Map<String, Object> instance = instance(biz);
		assertThat(instance.get("node_name")).as("AI 预审过了以后停在人工审批").isEqualTo("主管审批");
		JsonNode variable = json.readTree(String.valueOf(instance.get("variable")));
		assertThat(variable.path("ai_pass").asBoolean(false)).as("流程变量：" + variable).isTrue();
		assertThat(variable.path("ai_reason").asText()).isEqualTo("材料齐全");
		assertThat(variable.has("ai_error_code")).as("没有失败记录").isFalse();
	}

	@Test
	@DisplayName("办理上一道审批后进 AI 预审：应用在办理请求里运行一次，流程走到下一个审批节点")
	void aiNodeRunsTheAppWhenThePreviousStepIsHandled() throws Exception {
		String biz = "it-aipre-" + TS + "-handle";
		JsonNode started = readBody(post("/system/flow/start-by/" + AI_SECOND + "/" + biz, Map.of(), adminToken));
		assertThat(started.path("code").asInt()).as("发起：" + started.path("msg").asText()).isEqualTo(200);
		assertThat(instance(biz).get("node_name")).isEqualTo("部门初审");
		int runsBefore = successfulRuns();
		long taskId = jdbc.queryForObject(
			"select t.id from flow_task t join flow_instance i on i.id = t.instance_id where i.business_id = ?",
			Long.class, biz);

		JsonNode handled = readBody(post("/system/flow/task/handle/" + taskId, Map.of("message", "同意"), adminToken));

		assertThat(handled.path("code").asInt()).as("办理：" + handled.path("msg").asText()).isEqualTo(200);
		assertThat(successfulRuns() - runsBefore).as("AI 应用运行成功的次数").isEqualTo(1);
		Map<String, Object> instance = instance(biz);
		assertThat(instance.get("node_name")).isEqualTo("主管审批");
		assertThat(json.readTree(String.valueOf(instance.get("variable"))).path("ai_pass").asBoolean(false)).isTrue();
	}

	// ---------- 辅助 ----------

	private Map<String, Object> approval(String name, Map<String, Object> child) {
		Map<String, Object> node = new HashMap<>();
		node.put("type", "approval");
		node.put("name", name);
		node.put("role", "admin");
		if (child != null) {
			node.put("childNode", child);
		}
		return node;
	}

	/** 失败策略用「留在待办」：预审没运行时流程会停在 AI 预审节点，用例据此判断 */
	private Map<String, Object> aiNode(Map<String, Object> child) {
		Map<String, Object> node = new HashMap<>();
		node.put("type", "ai");
		node.put("name", "AI预审");
		node.put("aiFlowId", String.valueOf(appId));
		node.put("timeoutMs", 60000);
		node.put("onFailure", "stay");
		node.put("childNode", child);
		return node;
	}

	private void design(String flowCode, String flowName, Map<String, Object> root) {
		Map<String, Object> body = new HashMap<>();
		body.put("flowCode", flowCode);
		body.put("flowName", flowName);
		body.put("root", root);
		JsonNode r = readBody(post("/system/flow/design-graph", body, adminToken));
		assertThat(r.path("code").asInt()).as("建流程 " + flowCode + "：" + r.path("msg").asText()).isEqualTo(200);
		definitionIds.add(r.path("data").asLong());
	}

	private int successfulRuns() {
		return jdbc.queryForObject("select count(*) from ai_app_run where app_id = ? and status = 'success'",
			Integer.class, appId);
	}

	private Map<String, Object> instance(String businessId) {
		return jdbc.queryForMap("select node_name, variable from flow_instance where business_id = ?", businessId);
	}
}
