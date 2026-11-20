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
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 待办的选人列表 {@code GET /system/flow/task/user-select}：这条待办的办理人不需要用户管理的权限，
 * 就能取到本租户的启用用户并把待办转给选中的人；不是办理人、别的租户、待办已经办完，都取不到。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlowTaskUserSelectApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final String STARTER = "it_tus_s_" + TS;
	private static final String HANDLER = "it_tus_h_" + TS;
	private static final String OUTSIDER = "it_tus_o_" + TS;
	private static final String TARGET = "it_tus_t_" + TS;
	private static final String DISABLED = "it_tus_d_" + TS;
	private static final String TARGET_NICKNAME = "NickPick" + TS;
	private static final String TARGET_LABEL = TARGET_NICKNAME + "（" + TARGET + "）";
	private final String flowCode = "it_tus_" + TS.substring(TS.length() - 8);
	private String adminToken;
	private String starterToken;
	private String handlerToken;
	private String outsiderToken;
	private String targetToken;
	private String otherTenantAdminToken;
	private long handlerId;
	private long targetId;
	private long disabledId;
	private long definitionId;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		// 都是没有任何角色的普通员工：发起人、拿到待办的办理人、不相干的同事、被选中的同事、一个停用的账号
		createUser(STARTER, "集成测试-" + STARTER);
		handlerId = createUser(HANDLER, "集成测试-" + HANDLER);
		createUser(OUTSIDER, "集成测试-" + OUTSIDER);
		targetId = createUser(TARGET, TARGET_NICKNAME);
		disabledId = createUser(DISABLED, "集成测试-" + DISABLED);
		jdbc.update("update sys_user set status = 0 where id = ?", disabledId);
		starterToken = loginNewUser(PLATFORM_TENANT, STARTER, NEW_USER_PASSWORD);
		handlerToken = loginNewUser(PLATFORM_TENANT, HANDLER, NEW_USER_PASSWORD);
		outsiderToken = loginNewUser(PLATFORM_TENANT, OUTSIDER, NEW_USER_PASSWORD);
		targetToken = loginNewUser(PLATFORM_TENANT, TARGET, NEW_USER_PASSWORD);

		Map<String, Object> tenant = new HashMap<>();
		tenant.put("tenantName", "集成测试-待办选人-" + TS);
		tenant.put("contactUser", "集成测试");
		JsonNode created = readBody(post("/system/tenant/create", tenant, adminToken));
		assertThat(created.path("code").asInt()).as("建租户：" + created.path("msg").asText()).isEqualTo(200);
		String otherTenant = tenantCodeOf(created);
		normalizeTenantAdminPassword(otherTenant);
		otherTenantAdminToken = login(otherTenant, ADMIN_USERNAME, ADMIN_PASSWORD);

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
		body.put("flowName", "IT待办选人-" + TS);
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
	void handlerWithoutUserPermissionPicksAColleagueAndTransfers() {
		String biz = biz("pick");
		long taskId = giveTaskTo(biz, handlerToken, handlerId);

		// 前提：办理人没有用户管理的权限，用户管理那个选人接口对他是拒绝的
		JsonNode userSelect = readBody(get("/system/user/select", handlerToken));
		assertThat(userSelect.path("code").asInt()).as("没有 sys:user:list 时 /system/user/select 应被拒").isNotEqualTo(200);

		JsonNode all = select(handlerToken, taskId, null);
		assertThat(all.path("code").asInt()).as("办理人取候选人：" + all.path("msg").asText()).isEqualTo(200);
		assertThat(all.path("data").size()).as("默认列表有人，且不超过上限").isBetween(1, 50);
		String ids = toList(all, "value").stream().collect(Collectors.joining(","));
		Integer outOfScope = jdbc.queryForObject("select count(*) from sys_user where id in (" + ids + ") "
			+ "and (tenant_id <> ? or status <> 1 or coalesce(is_deleted, 0) <> 0)", Integer.class, PLATFORM_TENANT);
		assertThat(outOfScope).as("候选人都是本租户的启用用户").isZero();

		JsonNode byUsername = select(handlerToken, taskId, TARGET);
		assertThat(toList(byUsername, "label")).as("按用户名搜").containsExactly(TARGET_LABEL);
		assertThat(byUsername.path("data").get(0).path("value").asLong()).isEqualTo(targetId);
		assertThat(toList(select(handlerToken, taskId, TARGET_NICKNAME), "label")).as("按昵称搜").containsExactly(TARGET_LABEL);

		JsonNode moved = operate(handlerToken, taskId, "transfer", targetId);
		assertThat(moved.path("code").asInt()).as("转办给选中的人：" + moved.path("msg").asText()).isEqualTo(200);
		assertThat(todoOf(targetToken, biz)).as("对方的待办里出现这张单子").isNotNull();
		assertThat(todoOf(handlerToken, biz)).as("转出去以后不在自己的待办里").isNull();
		assertRefused(select(handlerToken, taskId, null), "转出去以后不再是办理人");
	}

	@Test
	void disabledAccountsAreNotOffered() {
		long taskId = giveTaskTo(biz("disabled"), handlerToken, handlerId);

		assertThat(toList(select(handlerToken, taskId, DISABLED), "label")).as("停用的账号不在候选里").isEmpty();
	}

	@Test
	void someoneWhoIsNotTheHandlerGetsNothing() {
		long taskId = giveTaskTo(biz("outsider"), handlerToken, handlerId);

		JsonNode r = select(outsiderToken, taskId, null);
		assertRefused(r, "不相干的同事");
		assertThat(r.path("msg").asText()).contains("办理人");
		assertRefused(select(starterToken, taskId, null), "发起人");
		// 平台超管和抄送一样放行：待办已经转给别人，他不再是办理人
		assertThat(select(adminToken, taskId, null).path("code").asInt()).as("平台超管").isEqualTo(200);
	}

	@Test
	void adminOfAnotherTenantGetsNothing() {
		// 待办留在「管理员」角色手里：别的租户的管理员角色码也叫 admin，光看办理人标识对得上，要靠租户归属拦住
		long taskId = startAndFindTask(biz("tenant"));

		assertRefused(select(otherTenantAdminToken, taskId, null), "别的租户的管理员");
	}

	@Test
	void finishedTaskOffersNothing() {
		long taskId = startAndFindTask(biz("done"));
		JsonNode pass = readBody(post("/system/flow/task/handle/" + taskId, Map.of("message", "同意"), adminToken));
		assertThat(pass.path("code").asInt()).as("办理：" + pass.path("msg").asText()).isEqualTo(200);

		JsonNode r = select(adminToken, taskId, null);
		assertRefused(r, "已经办完的待办");
		assertThat(r.path("msg").asText()).contains("已经办完");
	}

	@Test
	void userManagementSelectAnswersTheSameAsBefore() {
		// 用户管理的选人接口和待办选人共用查询条件和选项格式：按关键字搜到同一个选项，按 id 取时不看启用状态
		JsonNode byKeyword = readBody(get("/system/user/select?keyword=" + TARGET, adminToken));
		assertThat(toList(byKeyword, "label")).containsExactly(TARGET_LABEL);
		assertThat(toList(readBody(get("/system/user/select?keyword=" + DISABLED, adminToken)), "label")).isEmpty();

		JsonNode byIds = readBody(get("/system/user/select?ids=" + targetId + "," + disabledId, adminToken));
		assertThat(toList(byIds, "value"))
			.containsExactlyInAnyOrder(String.valueOf(targetId), String.valueOf(disabledId));
	}

	@Test
	void copyIsStillOnlyForTheHandler() {
		long taskId = giveTaskTo(biz("copy"), handlerToken, handlerId);
		Map<String, Object> body = Map.of("userIds", List.of(String.valueOf(targetId)));

		JsonNode byOutsider = readBody(post("/system/flow/task/copy/" + taskId, body, outsiderToken));
		assertThat(byOutsider.path("code").asInt()).as("不是办理人不能抄送").isNotEqualTo(200);
		assertThat(byOutsider.path("msg").asText()).contains("办理人");

		JsonNode byHandler = readBody(post("/system/flow/task/copy/" + taskId, body, handlerToken));
		assertThat(byHandler.path("code").asInt()).as("办理人抄送：" + byHandler.path("msg").asText()).isEqualTo(200);
	}

	// ---------- 辅助 ----------

	private String biz(String tag) {
		return "it-tus-" + TS + "-" + tag;
	}

	private JsonNode select(String token, long taskId, String keyword) {
		String url = "/system/flow/task/user-select?taskId=" + taskId + (keyword == null ? "" : "&keyword=" + keyword);
		return readBody(get(url, token));
	}

	private JsonNode operate(String token, long taskId, String op, long toUserId) {
		Map<String, Object> body = new HashMap<>();
		body.put("op", op);
		body.put("handlers", List.of(String.valueOf(toUserId)));
		body.put("message", "集成测试");
		return readBody(post("/system/flow/task/operation/" + taskId, body, token));
	}

	/** 发起一张单子，返回落在管理员角色手里的待办编号 */
	private long startAndFindTask(String biz) {
		JsonNode r = readBody(post("/system/flow/start-by/" + flowCode + "/" + biz, Map.of(), starterToken));
		assertThat(r.path("code").asInt()).as("发起：" + r.path("msg").asText()).isEqualTo(200);
		JsonNode task = todoOf(adminToken, biz);
		assertThat(task).as("设计的审批人（管理员角色）收到待办").isNotNull();
		return task.path("taskId").asLong();
	}

	/** 发起一张单子，由管理员转办给指定的人，返回这个人待办里的任务编号 */
	private long giveTaskTo(String biz, String token, long userId) {
		JsonNode r = operate(adminToken, startAndFindTask(biz), "transfer", userId);
		assertThat(r.path("code").asInt()).as("管理员转办：" + r.path("msg").asText()).isEqualTo(200);
		JsonNode mine = todoOf(token, biz);
		assertThat(mine).as("被转办的人收到待办").isNotNull();
		return mine.path("taskId").asLong();
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

	/** 接口成功时，把每个选项的某一项取成列表 */
	private static List<String> toList(JsonNode r, String field) {
		assertThat(r.path("code").asInt()).as("取候选人：" + r.path("msg").asText()).isEqualTo(200);
		List<String> values = new ArrayList<>();
		r.path("data").forEach(option -> values.add(option.path(field).asText()));
		return values;
	}

	private static void assertRefused(JsonNode r, String who) {
		assertThat(r.path("code").asInt()).as(who + "应被拒").isNotEqualTo(200);
		assertThat(r.path("data").size()).as(who + "不该拿到任何人").isZero();
	}

	/** 平台管理员建一个不带角色的用户，返回用户 id */
	private long createUser(String username, String nickname) {
		Map<String, Object> user = new HashMap<>();
		user.put("username", username);
		user.put("nickname", nickname);
		user.put("password", NEW_USER_PASSWORD);
		user.put("status", 1);
		user.put("roleIds", List.of());
		JsonNode r = readBody(post("/system/user/submit", user, adminToken));
		assertThat(r.path("code").asInt()).as("建用户 " + username + "：" + r.path("msg").asText()).isEqualTo(200);
		return jdbc.queryForObject(
			"select id from sys_user where username = ? and tenant_id = ? and coalesce(is_deleted, 0) = 0",
			Long.class, username, PLATFORM_TENANT);
	}
}
