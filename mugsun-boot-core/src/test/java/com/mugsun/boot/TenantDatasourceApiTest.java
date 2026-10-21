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
 * 租户独立数据源：校验失败、成功注册到同容器附加库、密码脱敏、删除注销。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantDatasourceApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final String EXTRA_DB = "mugsun_tds_it";
	/** 数据源只能挂在现存租户上：用接口建一个真实租户，取它的编号 */
	private String tenantCode;

	private String adminToken;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		ensureExtraDatabase(EXTRA_DB);
		tenantCode = createTenant("数据源测试租户");
	}

	private String createTenant(String name) {
		JsonNode resp = readBody(post("/system/tenant/create", Map.of("tenantName", name), adminToken));
		assertThat(resp.path("code").asInt()).as("建租户：" + resp.path("msg").asText()).isEqualTo(200);
		return resp.path("data").asText();
	}

	private Map<String, Object> body(String code) {
		Map<String, Object> body = new HashMap<>();
		body.put("tenantCode", code);
		body.put("dsUrl", jdbcUrlForDatabase(EXTRA_DB));
		body.put("dsUsername", primaryJdbcUsername());
		body.put("dsPassword", primaryJdbcPassword());
		body.put("isolationType", 1);
		body.put("status", 0);
		return body;
	}

	private long idOf(String code) {
		JsonNode page = readBody(get("/system/tenant-datasource/page?pageNum=1&pageSize=200", adminToken));
		for (JsonNode row : page.path("data").path("records")) {
			if (code.equals(row.path("tenantCode").asText())) {
				return row.path("id").asLong();
			}
		}
		return -1;
	}

	@Test
	void unauthenticatedIsUnauthorized() {
		ResponseEntity<String> resp = get("/system/tenant-datasource/page?pageNum=1&pageSize=10", null);
		assertThat(resp.getStatusCode().value()).isEqualTo(401);
	}

	@Test
	void validationRejectsBlankAndBadSchema() {
		JsonNode blank = readBody(post("/system/tenant-datasource/submit", Map.of("tenantCode", ""), adminToken));
		assertThat(blank.path("code").asInt()).isNotEqualTo(200);

		Map<String, Object> badSchema = new HashMap<>();
		badSchema.put("tenantCode", "bad" + TS.substring(TS.length() - 4));
		badSchema.put("dsUrl", jdbcUrlForDatabase(EXTRA_DB));
		badSchema.put("dsUsername", primaryJdbcUsername());
		badSchema.put("dsPassword", primaryJdbcPassword());
		badSchema.put("isolationType", 2);
		badSchema.put("schemaName", "bad-name!");
		badSchema.put("status", 0);
		JsonNode schema = readBody(post("/system/tenant-datasource/submit", badSchema, adminToken));
		assertThat(schema.path("code").asInt()).isNotEqualTo(200);
		assertThat(schema.path("msg").asText()).contains("schema");
	}

	@Test
	void submitRegisterMasksPasswordAndRemove() {
		Map<String, Object> body = body(tenantCode);
		body.put("status", 1);
		body.put("remark", "集成测试独立库");
		JsonNode submit = readBody(post("/system/tenant-datasource/submit", body, adminToken));
		assertThat(submit.path("code").asInt()).as("提交：" + submit.path("msg").asText()).isEqualTo(200);

		JsonNode page = readBody(get("/system/tenant-datasource/page?pageNum=1&pageSize=50", adminToken));
		assertThat(page.path("code").asInt()).isEqualTo(200);
		long id = -1;
		for (JsonNode row : page.path("data").path("records")) {
			if (tenantCode.equals(row.path("tenantCode").asText())) {
				id = row.path("id").asLong();
				assertThat(row.path("dsPassword").asText()).isEqualTo("******");
				break;
			}
		}
		assertThat(id).isPositive();

		JsonNode remove = readBody(post("/system/tenant-datasource/remove", List.of(id), adminToken));
		assertThat(remove.path("code").asInt()).isEqualTo(200);
	}

	@Test
	void createRejectsUnknownOrDeletedTenant() {
		JsonNode unknown = readBody(post("/system/tenant-datasource/submit", body("nx" + TS.substring(TS.length() - 6)), adminToken));
		assertThat(unknown.path("code").asInt()).isEqualTo(400);
		assertThat(unknown.path("msg").asText()).contains("不存在或已删除");

		String gone = createTenant("数据源测试-已删除");
		JsonNode list = readBody(get("/system/tenant/list?tenantCode=" + gone, adminToken));
		long tenantId = -1;
		for (JsonNode row : list.path("data")) {
			if (gone.equals(row.path("tenantCode").asText())) {
				tenantId = row.path("id").asLong();
			}
		}
		assertThat(tenantId).isPositive();
		assertThat(readBody(post("/system/tenant/remove", List.of(tenantId), adminToken)).path("code").asInt()).isEqualTo(200);
		JsonNode deleted = readBody(post("/system/tenant-datasource/submit", body(gone), adminToken));
		assertThat(deleted.path("code").asInt()).as("已删除租户不能再建数据源").isEqualTo(400);
		assertThat(idOf(gone)).isNegative();
	}

	@Test
	void softDeleteThenRecreateSameTenantCodeSucceeds() {
		String code = createTenant("数据源删后重建");
		JsonNode first = readBody(post("/system/tenant-datasource/submit", body(code), adminToken));
		assertThat(first.path("code").asInt()).as("首次提交：" + first.path("msg").asText()).isEqualTo(200);
		long id = idOf(code);
		assertThat(id).isPositive();
		assertThat(readBody(post("/system/tenant-datasource/remove", List.of(id), adminToken)).path("code").asInt()).isEqualTo(200);

		JsonNode again = readBody(post("/system/tenant-datasource/submit", body(code), adminToken));
		assertThat(again.path("code").asInt()).as("删后同编号重建：" + again.path("msg").asText()).isEqualTo(200);
		long id2 = idOf(code);
		assertThat(id2).isPositive().isNotEqualTo(id);
		readBody(post("/system/tenant-datasource/remove", List.of(id2), adminToken));
	}
}
