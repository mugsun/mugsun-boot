package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导出/导入独立权限码回归：只有列表读权（sys:user:list）不能导出，只有新增权（sys:user:add）不能导入；
 * 持 sys:user:export / sys:user:import 才放行。V13 迁移须已落按钮菜单。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExportImportPermissionApiTest extends AbstractIntegrationTest {

	private static final String TS = Long.toString(System.currentTimeMillis() % 1_000_000L);
	private static final String PWD = "123456";

	private String adminToken;
	private String listOnlyToken;
	private String exportToken;
	private String addOnlyToken;
	private String importToken;
	private String noRoleToken;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		long listMenu = findMenuIdByPermission("sys:user:list");
		long addMenu = findMenuIdByPermission("sys:user:add");
		long exportMenu = findMenuIdByPermission("sys:user:export");
		long importMenu = findMenuIdByPermission("sys:user:import");

		listOnlyToken = userWithMenus("it-ep-list-" + TS, "it_ep_l_" + TS, List.of(listMenu));
		exportToken = userWithMenus("it-ep-exp-" + TS, "it_ep_e_" + TS, List.of(listMenu, exportMenu));
		addOnlyToken = userWithMenus("it-ep-add-" + TS, "it_ep_a_" + TS, List.of(addMenu));
		importToken = userWithMenus("it-ep-imp-" + TS, "it_ep_i_" + TS, List.of(importMenu));
		noRoleToken = userWithoutRole("it_ep_n_" + TS);
	}

	@Test
	void exportForbiddenWithListPermissionOnly() {
		assertForbidden(get("/system/user/export", listOnlyToken));
	}

	@Test
	void exportAllowedWithExportPermission() {
		ResponseEntity<byte[]> r = getBytes("/system/user/export", exportToken);
		assertThat(r.getStatusCode().value()).isEqualTo(200);
		assertThat(r.getHeaders().getContentType()).isNotNull();
		assertThat(r.getHeaders().getContentType().toString()).contains("spreadsheetml");
	}

	@Test
	void adminCanStillExport() {
		assertThat(getBytes("/system/user/export", adminToken).getStatusCode().value()).isEqualTo(200);
	}

	@Test
	void importTemplateForbiddenWithAddPermissionOnly() {
		assertForbidden(get("/system/user/import-template", addOnlyToken));
	}

	@Test
	void importTemplateAllowedWithImportPermission() {
		assertThat(getBytes("/system/user/import-template", importToken).getStatusCode().value()).isEqualTo(200);
	}

	@Test
	void importForbiddenWithAddPermissionOnly() {
		assertForbidden(post("/system/user/import", null, addOnlyToken));
	}

	@Test
	void regionExportForbiddenWithoutExportPermission() {
		assertForbidden(get("/system/region/export", exportToken));
	}

	@Test
	void fileUploadForbiddenForUserWithoutAnyRole() {
		assertForbidden(post("/system/file/upload", null, noRoleToken));
		assertForbidden(post("/system/file/presigned-put", Map.of("filename", "a.png"), noRoleToken));
	}

	@Test
	void formAndFlowWritesForbiddenForUserWithoutAnyRole() {
		assertForbidden(post("/system/flow/definition/publish/1", null, noRoleToken));
		assertForbidden(post("/system/flow/definition/unpublish/1", null, noRoleToken));
		assertForbidden(post("/system/flow/deploy", null, noRoleToken));
		assertForbidden(post("/system/flow/design", Map.of(), noRoleToken));
		assertForbidden(post("/system/flow/design-graph", Map.of(), noRoleToken));
		assertForbidden(post("/system/form/submit", Map.of(), noRoleToken));
		assertForbidden(post("/system/form/remove", List.of(1L), noRoleToken));
	}

	@Test
	void ruleTenantInitAndTenantWritesArePlatformOnly() {
		assertForbidden(post("/system/rule/tenant-init", null, noRoleToken));
		assertForbidden(post("/system/rule/tenant-init", null, exportToken));
		assertForbidden(post("/system/tenant/create", Map.of(), noRoleToken));
		assertForbidden(post("/system/tenant-package/submit", Map.of(), noRoleToken));
		assertForbidden(post("/system/tenant-datasource/submit", Map.of(), noRoleToken));
		JsonNode ok = readBody(post("/system/rule/tenant-init", null, adminToken));
		assertThat(ok.path("code").asInt()).isEqualTo(200);
	}

	@Test
	void fileUploadForbiddenWithoutUploadPermission() {
		// 迁移后才建的角色没有 sys:file:upload：上传被拒（注解在参数绑定之前生效）
		assertForbidden(post("/system/file/upload", null, listOnlyToken));
	}

	private void assertForbidden(ResponseEntity<String> response) {
		assertThat(response.getStatusCode().value()).isEqualTo(403);
		assertThat(readBody(response).path("code").asInt()).isEqualTo(403);
	}

	private ResponseEntity<byte[]> getBytes(String url, String token) {
		return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), byte[].class);
	}

	private String userWithMenus(String roleCode, String username, List<Long> menuIds) {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "集成测试导出权限-" + roleCode);
		role.put("roleCode", roleCode);
		role.put("sort", 90);
		role.put("dataScope", 1);
		JsonNode rr = readBody(post("/system/role/submit", role, adminToken));
		assertThat(rr.path("code").asInt()).as("建角色：" + rr.path("msg").asText()).isEqualTo(200);
		long roleId = findId("/system/role/page?pageNum=1&pageSize=200&roleCode=" + roleCode, "roleCode", roleCode);

		Map<String, Object> grantMenus = new HashMap<>();
		grantMenus.put("roleId", roleId);
		grantMenus.put("menuIds", menuIds);
		JsonNode gr = readBody(post("/system/role/grant", grantMenus, adminToken));
		assertThat(gr.path("code").asInt()).as("角色授菜单：" + gr.path("msg").asText()).isEqualTo(200);

		Map<String, Object> user = new HashMap<>();
		user.put("username", username);
		user.put("nickname", "集成测试-" + username);
		user.put("password", PWD);
		user.put("status", 1);
		JsonNode ur = readBody(post("/system/user/submit", user, adminToken));
		assertThat(ur.path("code").asInt()).as("建用户：" + ur.path("msg").asText()).isEqualTo(200);
		long userId = findId("/system/user/page?pageNum=1&pageSize=200&username=" + username, "username", username);

		Map<String, Object> grant = new HashMap<>();
		grant.put("userId", userId);
		grant.put("roleIds", List.of(roleId));
		JsonNode ug = readBody(post("/system/user/grant", grant, adminToken));
		assertThat(ug.path("code").asInt()).as("用户授权：" + ug.path("msg").asText()).isEqualTo(200);
		return login(PLATFORM_TENANT, username, PWD);
	}

	private String userWithoutRole(String username) {
		Map<String, Object> user = new HashMap<>();
		user.put("username", username);
		user.put("nickname", "集成测试-" + username);
		user.put("password", PWD);
		user.put("status", 1);
		JsonNode ur = readBody(post("/system/user/submit", user, adminToken));
		assertThat(ur.path("code").asInt()).as("建用户：" + ur.path("msg").asText()).isEqualTo(200);
		return login(PLATFORM_TENANT, username, PWD);
	}

	private long findId(String pageUrl, String field, String value) {
		JsonNode page = readBody(get(pageUrl, adminToken));
		for (JsonNode record : page.path("data").path("records")) {
			if (value.equals(record.path(field).asText())) {
				return record.path("id").asLong();
			}
		}
		throw new IllegalStateException("分页中未找到 " + field + "=" + value);
	}

	private long findMenuIdByPermission(String permission) {
		JsonNode tree = readBody(get("/system/menu/tree", adminToken));
		assertThat(tree.path("code").asInt()).isEqualTo(200);
		Long id = findMenuIdInNodes(tree.path("data"), permission);
		assertThat(id).as("菜单树中应存在权限码：" + permission).isNotNull();
		return id;
	}

	private Long findMenuIdInNodes(JsonNode nodes, String permission) {
		for (JsonNode node : nodes) {
			if (permission.equals(node.path("permission").asText(null))) {
				return node.path("id").asLong();
			}
			Long found = findMenuIdInNodes(node.path("children"), permission);
			if (found != null) {
				return found;
			}
		}
		return null;
	}
}
