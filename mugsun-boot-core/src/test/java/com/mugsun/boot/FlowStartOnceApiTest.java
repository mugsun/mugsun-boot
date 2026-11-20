package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同一个流程 + 业务单号只能有一条没办完的流程：发起请求慢（后面跟着 AI 预审时），等不及再点一次、或者两次请求同时到，
 * 都不能多出一条。前一次还在处理时拒绝，流程还在办（含被退回）时拒绝；办完、终止或发起人撤回后单号可以再用。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlowStartOnceApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final String BIZ_PREFIX = "it-once-" + TS + "-";
	private static final String STILL_RUNNING = "已经发起过这个流程";
	private static final String BEING_STARTED = "正在发起";
	private static final String APPLICANT = "it_once_a_" + TS;
	private static final String COLLEAGUE = "it_once_b_" + TS;
	private final String flowCode = "it_once_" + TS.substring(TS.length() - 8);
	private final String otherFlowCode = "it_once2_" + TS.substring(TS.length() - 8);
	private final List<Long> definitionIds = new ArrayList<>();
	private String adminToken;
	private String applicantToken;
	private String colleagueToken;

	@Autowired
	private StringRedisTemplate redis;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		// 发起人和同事都是没有角色的普通员工，审批人是管理员角色
		createUser(APPLICANT);
		createUser(COLLEAGUE);
		applicantToken = loginNewUser(PLATFORM_TENANT, APPLICANT, NEW_USER_PASSWORD);
		colleagueToken = loginNewUser(PLATFORM_TENANT, COLLEAGUE, NEW_USER_PASSWORD);
		definitionIds.add(design(flowCode));
		definitionIds.add(design(otherFlowCode));
		if (jdbc.queryForObject("select count(*) from flow_definition where flow_code = 'leave' and is_publish = 1 "
			+ "and coalesce(del_flag, '0') <> '1'", Integer.class) == 0) {
			JsonNode r = readBody(post("/system/flow/deploy", Map.of(), adminToken));
			assertThat(r.path("code").asInt()).as("部署请假流程：" + r.path("msg").asText()).isEqualTo(200);
		}
	}

	@AfterAll
	void stopInstancesAndSuspendDefinitions() {
		// 被退回、被撤回的流程不在「运行中」列表里，从审批人的待办里作废
		for (JsonNode row : readBody(get("/system/flow/my-todo", adminToken)).path("data")) {
			if (row.path("businessId").asText().startsWith(BIZ_PREFIX) && !"1".equals(row.path("flowStatus").asText())) {
				post("/system/flow/task/terminate/" + row.path("taskId").asLong(), Map.of("message", "集成测试结束，清理"), adminToken);
			}
		}
		for (String code : List.of(flowCode, otherFlowCode, "leave")) {
			for (JsonNode row : readBody(get("/system/flow/running?flowCode=" + code, adminToken)).path("data")) {
				if (!row.path("businessId").asText().startsWith(BIZ_PREFIX)) {
					continue;
				}
				Map<String, Object> stop = new HashMap<>();
				stop.put("instanceId", row.path("instanceId").asText());
				stop.put("reason", "集成测试结束，清理");
				post("/system/flow/running/terminate", stop, adminToken);
			}
		}
		for (Long id : definitionIds) {
			post("/system/flow/definition/suspend/" + id, Map.of(), adminToken);
		}
	}

	@Test
	void aSecondStartOfTheSameNumberIsRefusedWhileTheFlowIsStillBeingHandled() {
		String biz = biz("twice");
		JsonNode first = start(applicantToken, flowCode, biz);
		assertThat(first.path("code").asInt()).as("第一次发起：" + first.path("msg").asText()).isEqualTo(200);

		JsonNode again = start(applicantToken, flowCode, biz);
		assertRefused(again, STILL_RUNNING, "同一个人再发起一次");
		// 单号认的是这份申请，不是谁点的：同租户的另一个人拿同一个单号发起也不行
		assertRefused(start(colleagueToken, flowCode, biz), STILL_RUNNING, "同事拿同一个单号发起");

		assertThat(instanceCount(biz)).as("只有第一次发起留下流程").isEqualTo(1);
		assertThat(redis.hasKey(lockKey(flowCode, biz))).as("发起结束后占位已经放掉").isFalse();
	}

	@Test
	void startsArrivingAtTheSameMomentLeaveOneFlow() throws Exception {
		String biz = biz("race");
		int callers = 6;
		ExecutorService pool = Executors.newFixedThreadPool(callers);
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<JsonNode>> calls = new ArrayList<>();
			for (int i = 0; i < callers; i++) {
				Callable<JsonNode> call = () -> {
					go.await();
					return start(applicantToken, flowCode, biz);
				};
				calls.add(pool.submit(call));
			}
			go.countDown();

			int accepted = 0;
			for (Future<JsonNode> call : calls) {
				JsonNode r = call.get(60, TimeUnit.SECONDS);
				if (r.path("code").asInt() == 200) {
					accepted++;
				} else {
					assertThat(r.path("msg").asText()).as("被拦下的请求要说清是重复发起")
						.containsAnyOf(BEING_STARTED, STILL_RUNNING);
				}
			}
			assertThat(accepted).as("同时到的 " + callers + " 个发起只放行一个").isEqualTo(1);
			assertThat(instanceCount(biz)).as("只留下一条流程").isEqualTo(1);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void whileAStartIsStillBeingProcessedAnotherOneIsRefusedAndLeavesNothing() {
		String biz = biz("busy");
		String key = lockKey(flowCode, biz);
		// 前一次发起还没处理完（比如 AI 预审还在等模型）时，占位还在
		redis.opsForValue().set(key, "held-by-the-first-request", Duration.ofMinutes(1));
		try {
			assertRefused(start(applicantToken, flowCode, biz), BEING_STARTED, "前一次还在处理时再发起");
			assertThat(instanceCount(biz)).as("被拒的发起不留下流程").isZero();
			assertThat(redis.opsForValue().get(key)).as("被拒的请求不能把前一次的占位放掉")
				.isEqualTo("held-by-the-first-request");
		} finally {
			redis.delete(key);
		}

		JsonNode later = start(applicantToken, flowCode, biz);
		assertThat(later.path("code").asInt()).as("前一次处理完（没有留下流程）以后再发起：" + later.path("msg").asText())
			.isEqualTo(200);
		assertThat(instanceCount(biz)).isEqualTo(1);
	}

	@Test
	void aStartThatFailsDoesNotKeepTheNumberOccupied() {
		String biz = biz("fail");
		String missing = flowCode + "_none";
		JsonNode r = start(applicantToken, missing, biz);

		assertThat(r.path("code").asInt()).as("没有这个流程码，发起应失败").isNotEqualTo(200);
		assertThat(redis.hasKey(lockKey(missing, biz))).as("发起失败后占位也要放掉").isFalse();
		assertThat(instanceCount(biz)).isZero();
	}

	@Test
	void theNumberCanBeUsedAgainAfterTheFlowIsFinishedOrTerminated() {
		String finished = biz("done");
		long firstId = startOk(applicantToken, flowCode, finished);
		handle(todoOf(adminToken, finished));
		handle(todoOf(adminToken, finished));
		assertThat(statusOf(firstId)).as("两级都通过后流程办结").isEqualTo("8");
		JsonNode again = start(applicantToken, flowCode, finished);
		assertThat(again.path("code").asInt()).as("办结后用同一个单号再发起：" + again.path("msg").asText()).isEqualTo(200);
		assertThat(instanceCount(finished)).isEqualTo(2);

		String stopped = biz("stop");
		long stoppedId = startOk(applicantToken, flowCode, stopped);
		Map<String, Object> stop = new HashMap<>();
		stop.put("instanceId", String.valueOf(stoppedId));
		stop.put("reason", "材料不全，先停下");
		JsonNode terminated = readBody(post("/system/flow/running/terminate", stop, adminToken));
		assertThat(terminated.path("code").asInt()).as("终止：" + terminated.path("msg").asText()).isEqualTo(200);
		assertThat(statusOf(stoppedId)).isEqualTo("4");
		JsonNode afterStop = start(applicantToken, flowCode, stopped);
		assertThat(afterStop.path("code").asInt()).as("终止后用同一个单号再发起：" + afterStop.path("msg").asText()).isEqualTo(200);
	}

	@Test
	void aReturnedFlowStillHoldsTheNumberUntilItIsEnded() {
		String biz = biz("back");
		long id = startOk(applicantToken, flowCode, biz);
		handle(todoOf(adminToken, biz));
		JsonNode second = todoOf(adminToken, biz);
		assertThat(second.path("nodeCode").asText()).isEqualTo("approve2");
		JsonNode back = readBody(post("/system/flow/task/reject/" + second.path("taskId").asLong(),
			Map.of("message", "金额不对，退回重审"), adminToken));
		assertThat(back.path("code").asInt()).as("退回上一步：" + back.path("msg").asText()).isEqualTo(200);
		assertThat(statusOf(id)).as("退回后流程还在，状态是已退回").isEqualTo("9");

		assertRefused(start(applicantToken, flowCode, biz), STILL_RUNNING, "流程被退回、还没办完时再发起");
		assertThat(instanceCount(biz)).isEqualTo(1);

		// 办理人把这条流程作废以后，单号才放出来
		JsonNode voided = readBody(post("/system/flow/task/terminate/" + todoOf(adminToken, biz).path("taskId").asLong(),
			Map.of("message", "作废重提"), adminToken));
		assertThat(voided.path("code").asInt()).as("作废：" + voided.path("msg").asText()).isEqualTo(200);
		assertThat(statusOf(id)).isEqualTo("4");
		JsonNode again = start(applicantToken, flowCode, biz);
		assertThat(again.path("code").asInt()).as("作废后再发起：" + again.path("msg").asText()).isEqualTo(200);
	}

	@Test
	void aWithdrawnFlowGivesTheNumberBackToTheApplicant() {
		String biz = biz("revoke");
		long id = startOk(applicantToken, flowCode, biz);
		JsonNode revoked = readBody(post("/system/flow/task/revoke/" + id, Map.of("message", "填错了，先撤回"), applicantToken));
		assertThat(revoked.path("code").asInt()).as("撤回：" + revoked.path("msg").asText()).isEqualTo(200);
		assertThat(statusOf(id)).as("撤回后的状态是已撤销").isEqualTo("6");
		// 单号在撤回后放出来，是因为发起人撤回以后手里没有待办、自己处理不了这张单子；哪天撤回改成回到发起人手里，这条规则要一起重看
		assertThat(todoOf(applicantToken, biz)).as("撤回后发起人没有这张单子的待办：" + openTasks(id)).isNull();

		JsonNode again = start(applicantToken, flowCode, biz);
		assertThat(again.path("code").asInt()).as("撤回后用同一个单号重新提交：" + again.path("msg").asText()).isEqualTo(200);
		assertRefused(start(applicantToken, flowCode, biz), STILL_RUNNING, "重新提交的那条还在办时再发起");
		assertThat(instanceCount(biz)).as("撤回的一条 + 重新提交的一条").isEqualTo(2);
	}

	@Test
	void anotherNumberOrAnotherFlowIsNotAffected() {
		String biz = biz("other");
		startOk(applicantToken, flowCode, biz);

		JsonNode otherNumber = start(applicantToken, flowCode, biz("other2"));
		assertThat(otherNumber.path("code").asInt()).as("换一个单号：" + otherNumber.path("msg").asText()).isEqualTo(200);
		JsonNode otherFlow = start(applicantToken, otherFlowCode, biz);
		assertThat(otherFlow.path("code").asInt()).as("同一个单号走另一个流程：" + otherFlow.path("msg").asText()).isEqualTo(200);
		assertThat(instanceCount(biz)).as("两个流程各一条").isEqualTo(2);
	}

	@Test
	void theOldLeaveEntryAndTheLeaveFormFollowTheSameRule() {
		String biz = biz("leave");
		JsonNode first = readBody(post("/system/flow/start/" + biz, Map.of(), adminToken));
		assertThat(first.path("code").asInt()).as("旧入口发起请假：" + first.path("msg").asText()).isEqualTo(200);
		assertRefused(readBody(post("/system/flow/start/" + biz, Map.of(), adminToken)), STILL_RUNNING, "旧入口再发起一次");
		assertRefused(start(applicantToken, "leave", biz), STILL_RUNNING, "换成按流程码发起同一张请假单");
		assertThat(instanceCount(biz)).isEqualTo(1);

		String busy = biz("leave-busy");
		String key = lockKey("leave", busy);
		redis.opsForValue().set(key, "held-by-the-first-request", Duration.ofMinutes(1));
		try {
			Map<String, Object> leave = new HashMap<>();
			leave.put("businessId", busy);
			leave.put("startTime", "2026-12-01 09:00:00");
			leave.put("endTime", "2026-12-02 18:00:00");
			leave.put("reason", "集成测试请假");
			assertRefused(readBody(post("/system/flow/leave/start", leave, adminToken)), BEING_STARTED,
				"请假单前一次还在处理时再提交");
			assertThat(instanceCount(busy)).isZero();
		} finally {
			redis.delete(key);
		}
	}

	// ---------- 辅助 ----------

	private String biz(String tag) {
		return BIZ_PREFIX + tag;
	}

	/** 发起占位的键：租户 + 流程码 + 业务单号 */
	private static String lockKey(String code, String businessId) {
		return "mugsun:flow:start:" + PLATFORM_TENANT + ":" + code + ":" + businessId;
	}

	private JsonNode start(String token, String code, String businessId) {
		return readBody(post("/system/flow/start-by/" + code + "/" + businessId, Map.of(), token));
	}

	private long startOk(String token, String code, String businessId) {
		JsonNode r = start(token, code, businessId);
		assertThat(r.path("code").asInt()).as("发起 " + businessId + "：" + r.path("msg").asText()).isEqualTo(200);
		return r.path("data").asLong();
	}

	private void handle(JsonNode task) {
		assertThat(task).as("审批人的待办里应有这张单子").isNotNull();
		JsonNode r = readBody(post("/system/flow/task/handle/" + task.path("taskId").asLong(),
			Map.of("message", "同意"), adminToken));
		assertThat(r.path("code").asInt()).as("办理：" + r.path("msg").asText()).isEqualTo(200);
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

	private String statusOf(long instanceId) {
		return jdbc.queryForObject("select flow_status from flow_instance where id = ?", String.class, instanceId);
	}

	/** 这条流程当前的待办（节点 → 办理人用户名），只用在断言说明里 */
	private String openTasks(long instanceId) {
		return jdbc.queryForList("select t.node_code as node, su.username as holder "
			+ "from flow_task t left join flow_user u on u.associated = t.id "
			+ "left join sys_user su on cast(su.id as varchar(64)) = u.processed_by "
			+ "where t.instance_id = ?", instanceId).toString();
	}

	private long design(String code) {
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
		body.put("flowCode", code);
		body.put("flowName", "IT同一单号只发起一次-" + code);
		body.put("formKey", null);
		body.put("nodes", nodes);
		JsonNode r = readBody(post("/system/flow/design", body, adminToken));
		assertThat(r.path("code").asInt()).as("design：" + r.path("msg").asText()).isEqualTo(200);
		return r.path("data").asLong();
	}

	/** 平台管理员建一个不带角色的用户 */
	private void createUser(String username) {
		Map<String, Object> user = new HashMap<>();
		user.put("username", username);
		user.put("nickname", "集成测试-" + username);
		user.put("password", NEW_USER_PASSWORD);
		user.put("status", 1);
		user.put("roleIds", List.of());
		JsonNode r = readBody(post("/system/user/submit", user, adminToken));
		assertThat(r.path("code").asInt()).as("建用户 " + username + "：" + r.path("msg").asText()).isEqualTo(200);
	}

	private static void assertRefused(JsonNode r, String expectedWords, String what) {
		assertThat(r.path("code").asInt()).as(what + "应被拒").isNotEqualTo(200);
		assertThat(r.path("msg").asText()).as(what).contains(expectedWords);
	}
}
