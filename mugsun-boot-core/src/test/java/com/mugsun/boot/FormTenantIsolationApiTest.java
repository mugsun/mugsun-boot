package com.mugsun.boot;

import cn.hutool.core.util.IdUtil;
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
 * 低代码表单的租户隔离：第二租户的管理员看不到、改不了、删不掉平台租户的表单，读不到也提交不进它的填报；
 * 两个租户可以用同一个标识，各自的填报互不影响；流程表单先找本租户的同标识表单，没有再用平台的，不取其他租户的。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FormTenantIsolationApiTest extends AbstractIntegrationTest {

	private String adminToken;
	private String tenantCode;
	private String tenantToken;
	private String formKey;
	private long platformFormId;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		Map<String, Object> tenant = new HashMap<>();
		tenant.put("tenantName", "表单隔离测试租户");
		tenant.put("contactUser", "表单隔离");
		JsonNode created = readBody(post("/system/tenant/create", tenant, adminToken));
		assertThat(created.path("code").asInt()).as("建租户：" + created.path("msg").asText()).isEqualTo(200);
		tenantCode = tenantCodeOf(created);
		assertThat(tenantCode).isNotBlank().isNotEqualTo(PLATFORM_TENANT);
		normalizeTenantAdminPassword(tenantCode);
		tenantToken = login(tenantCode, ADMIN_USERNAME, ADMIN_PASSWORD);

		formKey = uniqueKey("it_form");
		assertOk(post("/system/form/submit", form(null, "平台表单", formKey), adminToken), "平台建表单");
		platformFormId = formId(formKey, PLATFORM_TENANT);
		assertOk(post("/system/form/submit-data/" + formKey, Map.of("who", "平台用户"), adminToken), "平台填报");
	}

	@Test
	void tenantAdminCannotSeeReadOrFillPlatformForm() {
		JsonNode page = readBody(get("/system/form/page?pageNum=1&pageSize=100", tenantToken));
		assertThat(page.path("code").asInt()).isEqualTo(200);
		for (JsonNode row : page.path("data").path("records")) {
			assertThat(row.path("tenantId").asText()).isEqualTo(tenantCode);
			assertThat(row.path("id").asLong()).isNotEqualTo(platformFormId);
		}
		assertRejected(get("/system/form/detail?id=" + platformFormId, tenantToken));
		assertRejected(get("/system/form/by-key/" + formKey, tenantToken));

		long platformRows = dataCount(formKey, PLATFORM_TENANT);
		assertRejected(post("/system/form/submit-data/" + formKey, Map.of("who", "租户用户"), tenantToken));
		assertThat(dataCount(formKey, PLATFORM_TENANT)).isEqualTo(platformRows);
		assertThat(dataCount(formKey, tenantCode)).isZero();

		JsonNode data = readBody(get("/system/form/data/" + formKey + "?pageNum=1&pageSize=10", tenantToken));
		assertThat(data.path("code").asInt()).isEqualTo(200);
		assertThat(data.path("data").path("totalRow").asLong()).as("读不到平台租户的填报").isZero();
	}

	@Test
	void tenantAdminCannotEditOrRemovePlatformForm() {
		assertRejected(post("/system/form/submit", form(platformFormId, "被租户改名", formKey), tenantToken));
		assertThat(jdbc.queryForObject("SELECT name FROM sys_form WHERE id = ?", String.class, platformFormId))
			.isEqualTo("平台表单");

		long platformRows = dataCount(formKey, PLATFORM_TENANT);
		assertRejected(post("/system/form/remove", List.of(platformFormId), tenantToken));
		assertThat(jdbc.queryForObject("SELECT is_deleted FROM sys_form WHERE id = ?", Integer.class, platformFormId)).isZero();
		assertThat(dataCount(formKey, PLATFORM_TENANT)).isEqualTo(platformRows);
	}

	@Test
	void sameKeyInTwoTenantsStaysSeparate() {
		String key = uniqueKey("it_shared");
		assertOk(post("/system/form/submit", form(null, "平台同标识表单", key), adminToken), "平台建表单");
		assertOk(post("/system/form/submit-data/" + key, Map.of("who", "平台用户"), adminToken), "平台填报");

		Map<String, Object> spoofed = form(null, "租户同标识表单", key);
		spoofed.put("tenantId", PLATFORM_TENANT);
		assertOk(post("/system/form/submit", spoofed, tenantToken), "租户用同一个标识建表单");
		long tenantFormId = formId(key, tenantCode);
		assertThat(jdbc.queryForObject(
			"SELECT name FROM sys_form WHERE form_key = ? AND tenant_id = ? AND is_deleted = 0", String.class, key, PLATFORM_TENANT))
			.as("请求体里的 tenantId 不算数，平台租户的表单不变").isEqualTo("平台同标识表单");

		assertOk(post("/system/form/submit-data/" + key, Map.of("who", "租户用户"), tenantToken), "租户填报");
		assertThat(dataCount(key, tenantCode)).isEqualTo(1);
		assertThat(dataCount(key, PLATFORM_TENANT)).isEqualTo(1);
		JsonNode tenantData = readBody(get("/system/form/data/" + key + "?pageNum=1&pageSize=10", tenantToken));
		assertThat(tenantData.path("data").path("totalRow").asLong()).isEqualTo(1);
		assertThat(tenantData.path("data").path("records").get(0).path("formData").asText()).contains("租户用户");

		assertOk(post("/system/form/remove", List.of(tenantFormId), tenantToken), "租户删自己的表单");
		assertThat(dataCount(key, tenantCode)).isZero();
		assertThat(dataCount(key, PLATFORM_TENANT)).as("平台租户同标识表单的填报不受影响").isEqualTo(1);
		assertThat(formId(key, PLATFORM_TENANT)).isPositive();
	}

	@Test
	void flowFormPrefersOwnTenantThenPlatform() {
		String key = uniqueKey("it_flow");
		String tenantOnlyKey = uniqueKey("it_flow_t");
		assertOk(post("/system/form/submit", form(null, "平台流程表单", key), adminToken), "平台建流程表单");
		long flowA = IdUtil.getSnowflakeNextId();
		long flowB = IdUtil.getSnowflakeNextId();
		try {
			String codeA = bindStartForm(flowA, key);
			String codeB = bindStartForm(flowB, tenantOnlyKey);

			assertThat(startFormName(codeA, tenantToken)).as("本租户没有时用平台表单").isEqualTo("平台流程表单");

			assertOk(post("/system/form/submit", form(null, "租户流程表单", key), tenantToken), "租户建同标识表单");
			assertOk(post("/system/form/submit", form(null, "租户独有表单", tenantOnlyKey), tenantToken), "租户建独有表单");
			assertThat(startFormName(codeA, tenantToken)).as("本租户有同标识表单时优先用自己的").isEqualTo("租户流程表单");
			assertThat(startFormName(codeA, adminToken)).isEqualTo("平台流程表单");
			assertThat(startFormName(codeB, tenantToken)).isEqualTo("租户独有表单");

			JsonNode other = readBody(get("/system/flow/form/start?flowCode=" + codeB, adminToken));
			assertThat(other.path("code").asInt()).isEqualTo(200);
			assertThat(other.path("data").path("hasForm").asBoolean()).as("不取其他租户的表单").isFalse();
		} finally {
			jdbc.update("DELETE FROM flow_node WHERE definition_id IN (?, ?)", flowA, flowB);
			jdbc.update("DELETE FROM flow_definition WHERE id IN (?, ?)", flowA, flowB);
		}
	}

	/** 已发布的流程定义，发起节点绑定表单；返回流程编码 */
	private String bindStartForm(long definitionId, String key) {
		String code = "it_form_flow_" + definitionId;
		jdbc.update("INSERT INTO flow_definition (id, flow_code, flow_name, version, is_publish) VALUES (?, ?, '表单隔离测试流程', '1', 1)",
			definitionId, code);
		jdbc.update("INSERT INTO flow_node (id, node_type, definition_id, node_code, node_name, version, form_path) "
			+ "VALUES (?, 0, ?, 'start', '开始', '1', ?)", IdUtil.getSnowflakeNextId(), definitionId, key);
		return code;
	}

	private String startFormName(String flowCode, String token) {
		JsonNode r = readBody(get("/system/flow/form/start?flowCode=" + flowCode, token));
		assertThat(r.path("code").asInt()).as(r.path("msg").asText()).isEqualTo(200);
		assertThat(r.path("data").path("hasForm").asBoolean()).isTrue();
		return r.path("data").path("formName").asText();
	}

	private static String uniqueKey(String prefix) {
		return prefix + "_" + IdUtil.getSnowflakeNextId();
	}

	private static Map<String, Object> form(Long id, String name, String key) {
		Map<String, Object> body = new HashMap<>();
		if (id != null) {
			body.put("id", id);
		}
		body.put("name", name);
		body.put("formKey", key);
		body.put("formSchema", "[]");
		body.put("formOption", "{}");
		return body;
	}

	private long formId(String key, String tenant) {
		Long id = jdbc.queryForObject("SELECT id FROM sys_form WHERE form_key = ? AND tenant_id = ? AND is_deleted = 0",
			Long.class, key, tenant);
		assertThat(id).isNotNull();
		return id;
	}

	private long dataCount(String key, String tenant) {
		Long n = jdbc.queryForObject("SELECT count(*) FROM sys_form_data WHERE form_key = ? AND tenant_id = ? AND is_deleted = 0",
			Long.class, key, tenant);
		return n == null ? 0 : n;
	}

	private void assertOk(ResponseEntity<String> response, String what) {
		JsonNode r = readBody(response);
		assertThat(r.path("code").asInt()).as(what + "：" + r.path("msg").asText()).isEqualTo(200);
	}

	private void assertRejected(ResponseEntity<String> response) {
		JsonNode r = readBody(response);
		assertThat(r.path("code").asInt()).as(r.toString()).isNotEqualTo(200);
		assertThat(r.path("msg").asText()).contains("不存在");
	}
}
