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
	private static final String TENANT_CODE = "itds" + TS.substring(TS.length() - 6);

	private String adminToken;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		ensureExtraDatabase(EXTRA_DB);
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
		Map<String, Object> body = new HashMap<>();
		body.put("tenantCode", TENANT_CODE);
		body.put("dsUrl", jdbcUrlForDatabase(EXTRA_DB));
		body.put("dsUsername", primaryJdbcUsername());
		body.put("dsPassword", primaryJdbcPassword());
		body.put("isolationType", 1);
		body.put("status", 1);
		body.put("remark", "集成测试独立库");
		JsonNode submit = readBody(post("/system/tenant-datasource/submit", body, adminToken));
		assertThat(submit.path("code").asInt()).as("提交：" + submit.path("msg").asText()).isEqualTo(200);

		JsonNode page = readBody(get("/system/tenant-datasource/page?pageNum=1&pageSize=50", adminToken));
		assertThat(page.path("code").asInt()).isEqualTo(200);
		long id = -1;
		for (JsonNode row : page.path("data").path("records")) {
			if (TENANT_CODE.equals(row.path("tenantCode").asText())) {
				id = row.path("id").asLong();
				assertThat(row.path("dsPassword").asText()).isEqualTo("******");
				break;
			}
		}
		assertThat(id).isPositive();

		JsonNode remove = readBody(post("/system/tenant-datasource/remove", List.of(id), adminToken));
		assertThat(remove.path("code").asInt()).isEqualTo(200);
	}
}
