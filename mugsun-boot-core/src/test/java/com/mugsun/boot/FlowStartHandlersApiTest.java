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
 * 按流程码发起时不能在请求里指定审批人：带了 handlers 一律拒绝（填自己、填不相干的同事、填本来就是审批人的都一样），
 * 也不留下实例；不带时照常发起，首个审批节点的待办归流程设计的审批人，发起人自己办不了。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlowStartHandlersApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final String REFUSED = "发起时不能指定审批人";
	private static final String APPLICANT = "it_sth_a_" + TS;
	private static final String COLLEAGUE = "it_sth_b_" + TS;
	private final String flowCode = "it_sth_" + TS.substring(TS.length() - 8);
	private String adminToken;
	private String applicantToken;
	private String colleagueToken;
	private long applicantId;
	private long colleagueId;
	private long definitionId;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		// 两个没有任何角色的普通员工：一个发起，另一个是不在审批人范围里的同事
		applicantId = createUser(APPLICANT);
		colleagueId = createUser(COLLEAGUE);
		applicantToken = loginNewUser(PLATFORM_TENANT, APPLICANT, NEW_USER_PASSWORD);
		colleagueToken = loginNewUser(PLATFORM_TENANT, COLLEAGUE, NEW_USER_PASSWORD);

		List<Map<String, Object>> nodes = new ArrayList<>();
		for (String name : List.of("一级审批", "二级审批")) {
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
		body.put("flowName", "IT发起不能指定审批人-" + TS);
		body.put("formKey", null);
		body.put("nodes", nodes);
		JsonNode r = readBody(post("/system/flow/design", body, adminToken));
		assertThat(r.path("code").asInt()).as("design：" + r.path("msg").asText()).isEqualTo(200);
		definitionId = r.path("data").asLong();
	}

	@AfterAll
	void stopInstancesAndSuspendDefinition() {
		for (JsonNode row : readBody(get("/system/flow/running?flowCode=" + flowCode, adminToken)).path("data")) {
			Map<String, Object> stop = new HashMap<>();
			stop.put("instanceId", row.path("instanceId").asText());
			stop.put("reason", "集成测试结束，清理");
			post("/system/flow/running/terminate", stop, adminToken);
		}
		post("/system/flow/definition/suspend/" + definitionId, Map.of(), adminToken);
	}

	@Test
	void namingOneselfAsApproverIsRefused() {
		String biz = biz("self");
		JsonNode r = start(applicantToken, biz, Map.of("handlers", List.of(String.valueOf(applicantId))));

		assertRefused(r, "填自己");
		assertThat(instanceCount(biz)).as("被拒的发起不留下实例").isZero();
		assertThat(todoOf(applicantToken, biz)).as("发起人自己的待办里没有这张单子").isNull();
	}

	@Test
	void namingAColleagueOutsideTheDesignedApproversIsRefused() {
		String biz = biz("other");
		JsonNode r = start(applicantToken, biz, Map.of("handlers", List.of(String.valueOf(colleagueId))));

		assertRefused(r, "填不相干的同事");
		assertThat(instanceCount(biz)).as("被拒的发起不留下实例").isZero();
		assertThat(todoOf(colleagueToken, biz)).as("那位同事的待办里没有这张单子").isNull();
	}

	@Test
	void namingADesignedApproverIsRefusedToo() {
		// 请求里就不该出现审批人：填的恰好是设计里的审批角色也不接受，省得留一条能试探的路
		String biz = biz("role");
		JsonNode r = start(applicantToken, biz, Map.of("handlers", List.of("role:admin")));

		assertRefused(r, "填设计里的审批角色");
		assertThat(instanceCount(biz)).isZero();
	}

	@Test
	void withoutHandlersTheTaskGoesToTheDesignedApprover() {
		String biz = biz("plain");
		JsonNode r = start(applicantToken, biz, Map.of());
		assertThat(r.path("code").asInt()).as("不带 handlers 发起：" + r.path("msg").asText()).isEqualTo(200);
		assertThat(instanceCount(biz)).isEqualTo(1);

		assertThat(todoOf(applicantToken, biz)).as("发起人不是审批人，待办里不该有自己的单子").isNull();
		JsonNode task = todoOf(adminToken, biz);
		assertThat(task).as("设计的审批人（管理员角色）收到待办").isNotNull();
		assertThat(task.path("nodeCode").asText()).isEqualTo("approve1");
		long taskId = task.path("taskId").asLong();

		// 发起人拿着任务编号直接调办理接口也办不了
		JsonNode byApplicant = readBody(post("/system/flow/task/handle/" + taskId, Map.of("message", "自己批"), applicantToken));
		assertThat(byApplicant.path("code").asInt()).as("发起人办理设计给别人的待办应被拒").isNotEqualTo(200);
		assertThat(todoOf(adminToken, biz).path("taskId").asLong()).as("任务仍停在一级审批").isEqualTo(taskId);

		JsonNode byAdmin = readBody(post("/system/flow/task/handle/" + taskId, Map.of("message", "同意"), adminToken));
		assertThat(byAdmin.path("code").asInt()).as("设计的审批人办理：" + byAdmin.path("msg").asText()).isEqualTo(200);
		assertThat(todoOf(adminToken, biz).path("nodeCode").asText()).isEqualTo("approve2");
	}

	@Test
	void emptyHandlersListCountsAsAbsent() {
		String biz = biz("empty");
		JsonNode r = start(applicantToken, biz, Map.of("handlers", List.of()));

		assertThat(r.path("code").asInt()).as("handlers 为空数组：" + r.path("msg").asText()).isEqualTo(200);
		assertThat(todoOf(applicantToken, biz)).isNull();
		assertThat(todoOf(adminToken, biz)).isNotNull();
	}

	// ---------- 辅助 ----------

	private String biz(String tag) {
		return "it-sth-" + TS + "-" + tag;
	}

	private JsonNode start(String token, String businessId, Map<String, Object> body) {
		return readBody(post("/system/flow/start-by/" + flowCode + "/" + businessId, body, token));
	}

	/** 这个人的待办里业务单号对得上的那一条；没有就返回 null */
	private JsonNode todoOf(String token, String businessId) {
		JsonNode r = readBody(get("/system/flow/my-todo", token));
		assertThat(r.path("code").asInt()).as("my-todo：" + r.path("msg").asText()).isEqualTo(200);
		for (JsonNode row : r.path("data")) {
			if (businessId.equals(row.path("businessId").asText())) {
				return row;
			}
		}
		return null;
	}

	private int instanceCount(String businessId) {
		return jdbc.queryForObject("SELECT count(*) FROM flow_instance WHERE business_id = ?", Integer.class, businessId);
	}

	/** 平台管理员建一个不带角色的用户，返回用户 id */
	private long createUser(String username) {
		Map<String, Object> user = new HashMap<>();
		user.put("username", username);
		user.put("nickname", "集成测试-" + username);
		user.put("password", NEW_USER_PASSWORD);
		user.put("status", 1);
		user.put("roleIds", List.of());
		JsonNode r = readBody(post("/system/user/submit", user, adminToken));
		assertThat(r.path("code").asInt()).as("建用户 " + username + "：" + r.path("msg").asText()).isEqualTo(200);
		return jdbc.queryForObject(
			"select id from sys_user where username = ? and tenant_id = ? and coalesce(is_deleted, 0) = 0",
			Long.class, username, PLATFORM_TENANT);
	}

	private static void assertRefused(JsonNode r, String what) {
		assertThat(r.path("code").asInt()).as(what + "应被拒").isNotEqualTo(200);
		assertThat(r.path("msg").asText()).as(what).contains(REFUSED);
	}
}
