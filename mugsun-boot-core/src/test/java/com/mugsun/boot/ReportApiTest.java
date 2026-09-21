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
 * 报表域回归：内置数据集白名单、设计保存/预览/删除、未知 key 拒执、未登录 401、低权写 403。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final String LOW_USERNAME = "it-rpt-none-" + TS;
	private static final String LOW_PASSWORD = "123456";

	private String adminToken;
	private String lowToken;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		zeroPermissionLogin(adminToken);
	}

	@Test
	void unauthenticatedIsUnauthorized() {
		assertThat(get("/system/report/list", null).getStatusCode().value()).isEqualTo(401);
	}

	@Test
	void datasetsAndPreviewWhitelist() {
		JsonNode datasets = readBody(get("/system/report/datasets", adminToken));
		assertThat(datasets.path("code").asInt()).isEqualTo(200);
		assertThat(datasets.path("data").size()).isGreaterThanOrEqualTo(2);

		JsonNode ok = readBody(get("/system/report/preview-dataset?key=user_status", adminToken));
		assertThat(ok.path("code").asInt()).as("白名单数据集应可预览：" + ok.path("msg").asText()).isEqualTo(200);
		assertThat(ok.path("data").isArray()).isTrue();

		ResponseEntity<String> bad = get("/system/report/preview-dataset?key=drop_table_evil", adminToken);
		JsonNode badBody = readBody(bad);
		assertThat(badBody.path("code").asInt()).as("未知 key 不得执行任意 SQL").isNotEqualTo(200);
		assertThat(badBody.path("success").asBoolean()).isFalse();
	}

	@Test
	void submitPreviewAndRemove() {
		Map<String, Object> report = new HashMap<>();
		report.put("reportName", "IT报表-" + TS);
		report.put("reportKey", "user_status");
		report.put("chartType", "pie");
		report.put("remark", "集成测试");
		JsonNode submit = readBody(post("/system/report/submit", report, adminToken));
		assertThat(submit.path("code").asInt()).as("保存：" + submit.path("msg").asText()).isEqualTo(200);

		JsonNode list = readBody(get("/system/report/list", adminToken));
		assertThat(list.path("code").asInt()).isEqualTo(200);
		long id = -1;
		for (JsonNode row : list.path("data")) {
			if (("IT报表-" + TS).equals(row.path("reportName").asText())) {
				id = row.path("id").asLong();
				break;
			}
		}
		assertThat(id).isPositive();

		JsonNode preview = readBody(get("/system/report/preview?id=" + id, adminToken));
		assertThat(preview.path("code").asInt()).as("预览：" + preview.path("msg").asText()).isEqualTo(200);
		assertThat(preview.path("data").isArray()).isTrue();

		JsonNode remove = readBody(post("/system/report/remove/" + id, Map.of(), adminToken));
		assertThat(remove.path("code").asInt()).isEqualTo(200);
	}

	@Test
	void lowPermissionCannotSave() {
		Map<String, Object> report = new HashMap<>();
		report.put("reportName", "低权越权-" + TS);
		report.put("reportKey", "user_status");
		report.put("chartType", "bar");
		ResponseEntity<String> resp = post("/system/report/submit", report, lowToken);
		assertThat(resp.getStatusCode().value()).isEqualTo(403);
		assertThat(readBody(resp).path("code").asInt()).isEqualTo(403);
	}

	private void zeroPermissionLogin(String platformAdmin) {
		String roleCode = "it-rpt-role-" + TS;
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "集成测试报表零权限角色");
		role.put("roleCode", roleCode);
		role.put("sort", 99);
		role.put("dataScope", 1);
		assertThat(readBody(post("/system/role/submit", role, platformAdmin)).path("code").asInt()).isEqualTo(200);

		Map<String, Object> user = new HashMap<>();
		user.put("username", LOW_USERNAME);
		user.put("nickname", "集成测试报表低权用户");
		user.put("password", LOW_PASSWORD);
		user.put("status", 1);
		assertThat(readBody(post("/system/user/submit", user, platformAdmin)).path("code").asInt()).isEqualTo(200);

		JsonNode rolePage = readBody(get("/system/role/page?pageNum=1&pageSize=200&roleCode=" + roleCode, platformAdmin));
		long roleId = -1;
		for (JsonNode record : rolePage.path("data").path("records")) {
			if (roleCode.equals(record.path("roleCode").asText())) {
				roleId = record.path("id").asLong();
			}
		}
		JsonNode userPage = readBody(get("/system/user/page?pageNum=1&pageSize=200&username=" + LOW_USERNAME, platformAdmin));
		long userId = -1;
		for (JsonNode record : userPage.path("data").path("records")) {
			if (LOW_USERNAME.equals(record.path("username").asText())) {
				userId = record.path("id").asLong();
			}
		}
		assertThat(roleId).isPositive();
		assertThat(userId).isPositive();
		Map<String, Object> grant = new HashMap<>();
		grant.put("userId", userId);
		grant.put("roleIds", List.of(roleId));
		assertThat(readBody(post("/system/user/grant", grant, platformAdmin)).path("code").asInt()).isEqualTo(200);
		lowToken = login(PLATFORM_TENANT, LOW_USERNAME, LOW_PASSWORD);
	}
}
