package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户隔离回归：第二租户管理员只见本租户数据；伪造 X-Tenant-Id 请求头被守卫拒绝（403）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantIsolationApiTest extends AbstractIntegrationTest {

	private String tenantCode;
	private String tenantAdminToken;

	@BeforeAll
	void setupSecondTenant() {
		String adminToken = loginAdmin();

		// 经 admin API 建第二个租户（自动初始化其 admin/123456 管理员）
		Map<String, Object> tenant = new HashMap<>();
		tenant.put("tenantName", "集成测试租户");
		tenant.put("contactUser", "集成测试");
		JsonNode createResp = readBody(post("/system/tenant/create", tenant, adminToken));
		assertThat(createResp.path("code").asInt()).as("建租户：" + createResp.path("msg").asText()).isEqualTo(200);
		tenantCode = createResp.path("data").asText();
		assertThat(tenantCode).isNotBlank().isNotEqualTo(PLATFORM_TENANT);

		// 该租户管理员真实登录
		tenantAdminToken = login(tenantCode, ADMIN_USERNAME, ADMIN_PASSWORD);
	}

	@Test
	void tenantAdminSeesOnlyOwnTenantUsers() {
		ResponseEntity<String> response = get("/system/user/page?pageNum=1&pageSize=100", tenantAdminToken);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		JsonNode r = readBody(response);
		assertThat(r.path("code").asInt()).isEqualTo(200);
		JsonNode records = r.path("data").path("records");
		assertThat(records.isArray()).isTrue();
		assertThat(records.size()).as("新租户仅有其管理员一个用户").isEqualTo(1);
		for (JsonNode user : records) {
			assertThat(user.path("tenantId").asText()).isEqualTo(tenantCode);
		}
		// 反向断言：分页结果绝不泄露平台租户（000000）用户
		java.util.List<String> tenantIds = new java.util.ArrayList<>();
		records.forEach(n -> tenantIds.add(n.path("tenantId").asText()));
		assertThat(tenantIds).doesNotContain(PLATFORM_TENANT);
	}

	@Test
	void forgedTenantHeaderIsRejected() {
		// 会话租户为第二租户，请求头伪造平台租户号 → 租户守卫 fail-closed
		ResponseEntity<String> response = getWithTenantHeader(
			"/system/user/page?pageNum=1&pageSize=10", tenantAdminToken, PLATFORM_TENANT);
		assertThat(response.getStatusCode().value()).isEqualTo(403);
		JsonNode r = readBody(response);
		assertThat(r.path("code").asInt()).isEqualTo(403);
		assertThat(r.path("success").asBoolean()).isFalse();
		assertThat(r.path("msg").asText()).contains("无权跨租户操作");
	}

	@Test
	void globalConfigWriteIsForbiddenForTenantAdmin() {
		// 租户管理员（持 * 通配）改全局配置（无 tenant_id 共享表）被拒——跨租户完整性红线
		Map<String, Object> dict = new HashMap<>();
		dict.put("code", "it-global-" + System.currentTimeMillis() % 100000);
		dict.put("dictValue", "越权字典");
		dict.put("dictKey", "1");
		ResponseEntity<String> response = post("/system/dict/submit", dict, tenantAdminToken);
		assertThat(response.getStatusCode().value()).as("租户管理员写全局字典须 403").isEqualTo(403);
		JsonNode r = readBody(response);
		assertThat(r.path("code").asInt()).isEqualTo(403);
		assertThat(r.path("msg").asText()).contains("仅平台超管可修改平台全局配置");

		// 平台超管不受影响（同端点可写）
		JsonNode ok = readBody(post("/system/dict/submit", dict, loginAdmin()));
		assertThat(ok.path("code").asInt()).as("平台超管写全局字典须 200：" + ok.path("msg").asText()).isEqualTo(200);
	}
}
