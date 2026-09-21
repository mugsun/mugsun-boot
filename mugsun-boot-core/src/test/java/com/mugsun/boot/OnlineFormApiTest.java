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
 * online 低代码引擎越权（G77）回归：无实体泛化 CRUD 手工附加租户范围，跨租户读/改/删一律落空。
 * 前置：平台超管建两个租户（容器内无现成租户种子，经 admin API 现建），各租户管理员真实登录、
 * 各建一张 online 物理表（含 tenant_id 隔离列）并插一行。
 * <p>断言矩阵：B 租户凭 A 的 tableId+行 id 读→data 为 null（不泄露存在性）；改→范围校验拒绝且原值不变；
 * 删→接口佯装成功但 A 侧行仍在（SQL 附加租户条件 0 行命中）；无 sys:online:list 码→403。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnlineFormApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final String TABLE_A = "gen_it_ola_" + TS;
	private static final String TABLE_B = "gen_it_olb_" + TS;
	private static final String TITLE_A = "租户A机密-" + TS;
	private static final String TITLE_B = "租户B数据-" + TS;
	private static final String LOW_USERNAME = "it-ol-none-" + TS;
	private static final String LOW_PASSWORD = "123456";

	private String tokenA;
	private String tokenB;
	private String tenantCodeA;
	private long tableIdA;
	private long rowIdA;
	private String lowToken;

	@BeforeAll
	void setupTenantsAndTables() {
		String platformAdmin = loginAdmin();

		// 1) 两个租户（各自初始化 admin/123456 管理员）
		tenantCodeA = createTenant(platformAdmin, "IT在线表单租户A-" + TS);
		String tenantCodeB = createTenant(platformAdmin, "IT在线表单租户B-" + TS);
		tokenA = login(tenantCodeA, ADMIN_USERNAME, ADMIN_PASSWORD);
		tokenB = login(tenantCodeB, ADMIN_USERNAME, ADMIN_PASSWORD);

		// 2) 各建 online 表（元数据 + 物理表，物理表含 tenant_id/is_deleted 审计列）
		tableIdA = createOnlineTable(tokenA, TABLE_A, "IT在线表单A");
		long tableIdB = createOnlineTable(tokenB, TABLE_B, "IT在线表单B");
		assertThat(tableIdB).isNotEqualTo(tableIdA);

		// 3) 各插一行（save 新增时按会话租户落 tenant_id 列）
		saveRow(tokenA, tableIdA, Map.of("title", TITLE_A));
		saveRow(tokenB, tableIdB, Map.of("title", TITLE_B));
		rowIdA = firstRowId(tokenA, tableIdA);

		// 4) 平台租户零权限用户（无任何菜单码），供权限码缺失用例
		zeroPermissionLogin(platformAdmin);
	}

	@Test
	void ownDetailReadsRow() {
		// 正向对照：A 读自家行，标题与租户归属列原样返回
		JsonNode r = readBody(get("/system/online-form/detail?tableId=" + tableIdA + "&id=" + rowIdA, tokenA));
		assertThat(r.path("code").asInt()).isEqualTo(200);
		JsonNode data = r.path("data");
		assertThat(data.isNull()).as("A 读自家行应有数据").isFalse();
		assertThat(data.path("title").asText()).isEqualTo(TITLE_A);
		assertThat(data.path("tenant_id").asText()).isEqualTo(tenantCodeA);
	}

	@Test
	void crossTenantDetailReturnsNull() {
		// B 凭 A 的 tableId + 行 id 直读：范围过滤后查无此行，返 null（不 403 但绝不泄露数据与存在性）
		JsonNode r = readBody(get("/system/online-form/detail?tableId=" + tableIdA + "&id=" + rowIdA, tokenB));
		assertThat(r.path("code").asInt()).isEqualTo(200);
		assertThat(r.path("data").isNull()).as("跨租户读应查无此行").isTrue();
	}

	@Test
	void crossTenantUpdateIsRejectedAndValueUntouched() {
		// B 凭 id 改 A 的行：编辑前范围校验查无此行 → 拒绝（现状为 IllegalArgumentException 走兜底 500，方向 fail-closed）
		Map<String, Object> tamper = new HashMap<>();
		tamper.put("id", rowIdA);
		tamper.put("title", "被B租户篡改");
		ResponseEntity<String> response = post("/system/online-form/save?tableId=" + tableIdA, tamper, tokenB);
		assertThat(response.getStatusCode().value()).isEqualTo(500);
		JsonNode r = readBody(response);
		assertThat(r.path("code").asInt()).as("跨租户改应被拒绝").isEqualTo(500);
		assertThat(r.path("success").asBoolean()).isFalse();

		// 关键断言：A 侧原值未被污染
		JsonNode after = readBody(get("/system/online-form/detail?tableId=" + tableIdA + "&id=" + rowIdA, tokenA));
		assertThat(after.path("data").path("title").asText()).isEqualTo(TITLE_A);
	}

	@Test
	void crossTenantRemoveSilentlySparesRow() {
		// B 凭 id 删 A 的行：DELETE/逻辑删 SQL 附加租户条件 0 行命中——接口佯装成功，行实际不被动
		ResponseEntity<String> response = post("/system/online-form/remove?tableId=" + tableIdA,
			List.of(rowIdA), tokenB);
		JsonNode r = readBody(response);
		assertThat(r.path("code").asInt()).isEqualTo(200);

		// 关键断言：A 侧行仍在且内容原样
		JsonNode after = readBody(get("/system/online-form/detail?tableId=" + tableIdA + "&id=" + rowIdA, tokenA));
		assertThat(after.path("data").isNull()).as("跨租户删不得生效").isFalse();
		assertThat(after.path("data").path("title").asText()).isEqualTo(TITLE_A);
	}

	@Test
	void missingOnlineListPermissionIsForbidden() {
		// sys:online:list 读码缺失 → @SaCheckPermission 原生拒绝（forms 与 page 同码）
		ResponseEntity<String> forms = get("/system/online-form/forms", lowToken);
		assertThat(forms.getStatusCode().value()).isEqualTo(403);
		assertThat(readBody(forms).path("code").asInt()).isEqualTo(403);

		ResponseEntity<String> page = get("/system/online-form/page?tableId=" + tableIdA + "&pageNum=1&pageSize=10", lowToken);
		assertThat(page.getStatusCode().value()).isEqualTo(403);
		assertThat(readBody(page).path("code").asInt()).isEqualTo(403);
	}

	// ---------- 前置构造辅助 ----------

	private String createTenant(String platformAdmin, String tenantName) {
		Map<String, Object> tenant = new HashMap<>();
		tenant.put("tenantName", tenantName);
		tenant.put("contactUser", "集成测试");
		JsonNode r = readBody(post("/system/tenant/create", tenant, platformAdmin));
		assertThat(r.path("code").asInt()).as("建租户：" + r.path("msg").asText()).isEqualTo(200);
		String code = r.path("data").asText();
		assertThat(code).isNotBlank().isNotEqualTo(PLATFORM_TENANT);
		return code;
	}

	/** 确认建模（元数据落库 + 立即建物理表），返回表配置 id */
	private long createOnlineTable(String token, String tableName, String comment) {
		Map<String, Object> column = new HashMap<>();
		column.put("columnName", "title");
		column.put("columnComment", "标题");
		column.put("javaType", "String");
		Map<String, Object> table = new HashMap<>();
		table.put("tableName", tableName);
		table.put("tableComment", comment);
		Map<String, Object> confirm = new HashMap<>();
		confirm.put("table", table);
		confirm.put("columns", List.of(column));
		confirm.put("build", true);
		JsonNode r = readBody(post("/system/gen/ai/confirm", confirm, token));
		assertThat(r.path("code").asInt()).as("建 online 表：" + r.path("msg").asText()).isEqualTo(200);
		long tableId = r.path("data").asLong();
		assertThat(tableId).isPositive();
		return tableId;
	}

	private void saveRow(String token, long tableId, Map<String, Object> data) {
		JsonNode r = readBody(post("/system/online-form/save?tableId=" + tableId, data, token));
		assertThat(r.path("code").asInt()).as("插入行：" + r.path("msg").asText()).isEqualTo(200);
	}

	private long firstRowId(String token, long tableId) {
		JsonNode r = readBody(get("/system/online-form/page?tableId=" + tableId + "&pageNum=1&pageSize=10", token));
		assertThat(r.path("code").asInt()).isEqualTo(200);
		JsonNode records = r.path("data").path("records");
		assertThat(records.size()).as("插入后应恰一行").isEqualTo(1);
		return records.get(0).path("id").asLong();
	}

	/** 平台租户零权限用户（角色不授任何菜单），真实登录拿 token */
	private void zeroPermissionLogin(String platformAdmin) {
		String roleCode = "it-ol-role-" + TS;
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "集成测试在线表单零权限角色");
		role.put("roleCode", roleCode);
		role.put("sort", 99);
		role.put("dataScope", 1);
		JsonNode roleResp = readBody(post("/system/role/submit", role, platformAdmin));
		assertThat(roleResp.path("code").asInt()).as("建角色：" + roleResp.path("msg").asText()).isEqualTo(200);

		Map<String, Object> user = new HashMap<>();
		user.put("username", LOW_USERNAME);
		user.put("nickname", "集成测试在线表单低权用户");
		user.put("password", LOW_PASSWORD);
		user.put("status", 1);
		JsonNode userResp = readBody(post("/system/user/submit", user, platformAdmin));
		assertThat(userResp.path("code").asInt()).as("建用户：" + userResp.path("msg").asText()).isEqualTo(200);

		JsonNode rolePage = readBody(get("/system/role/page?pageNum=1&pageSize=200&roleCode=" + roleCode, platformAdmin));
		long roleId = -1;
		for (JsonNode record : rolePage.path("data").path("records")) {
			if (roleCode.equals(record.path("roleCode").asText())) {
				roleId = record.path("id").asLong();
			}
		}
		assertThat(roleId).isPositive();
		JsonNode userPage = readBody(get("/system/user/page?pageNum=1&pageSize=200&username=" + LOW_USERNAME, platformAdmin));
		long userId = -1;
		for (JsonNode record : userPage.path("data").path("records")) {
			if (LOW_USERNAME.equals(record.path("username").asText())) {
				userId = record.path("id").asLong();
			}
		}
		assertThat(userId).isPositive();

		Map<String, Object> grant = new HashMap<>();
		grant.put("userId", userId);
		grant.put("roleIds", List.of(roleId));
		JsonNode grantResp = readBody(post("/system/user/grant", grant, platformAdmin));
		assertThat(grantResp.path("code").asInt()).as("授权：" + grantResp.path("msg").asText()).isEqualTo(200);

		lowToken = login(PLATFORM_TENANT, LOW_USERNAME, LOW_PASSWORD);
	}
}
