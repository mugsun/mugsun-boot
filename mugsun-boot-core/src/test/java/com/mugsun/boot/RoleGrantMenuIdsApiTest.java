package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #86：角色授权 menuIds 校验——非管理员不可授出自身菜单外的 id；不存在 id 拒绝；管理员可授存在菜单。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoleGrantMenuIdsApiTest extends AbstractIntegrationTest {

	private static final String TS = Long.toString(System.currentTimeMillis() % 1_000_000L);
	private static final String PWD = "123456";
	private static final String GRANTER_ROLE = "it-rg-gr-" + TS;
	private static final String GRANTER_USER = "it_rg_g_" + TS;
	private static final String TARGET_ROLE = "it-rg-tg-" + TS;

	private String adminToken;
	private String granterToken;
	private long targetRoleId;
	private long grantMenuId;
	private long ownExtraMenuId;
	private long outsideMenuId;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		long rolePageId = findMenuIdByPath("/system/role");
		grantMenuId = ensureButton(rolePageId, "角色授权", "sys:role:grant", 20);
		ownExtraMenuId = findMenuIdByPermission("sys:user:list");
		outsideMenuId = findMenuIdByPermission("sys:user:add");
		assertThat(outsideMenuId).as("越权目标菜单应与自身菜单不同").isNotEqualTo(ownExtraMenuId);

		granterToken = userWithMenus(GRANTER_ROLE, GRANTER_USER, List.of(grantMenuId, ownExtraMenuId));

		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "集成测试授权目标-" + TS);
		role.put("roleCode", TARGET_ROLE);
		role.put("sort", 91);
		role.put("dataScope", 1);
		JsonNode rr = readBody(post("/system/role/submit", role, adminToken));
		assertThat(rr.path("code").asInt()).as("建目标角色：" + rr.path("msg").asText()).isEqualTo(200);
		targetRoleId = findId("/system/role/page?pageNum=1&pageSize=200&roleCode=" + TARGET_ROLE, "roleCode", TARGET_ROLE);

		Map<String, Object> seed = new HashMap<>();
		seed.put("roleId", targetRoleId);
		seed.put("menuIds", List.of(ownExtraMenuId));
		JsonNode seeded = readBody(post("/system/role/grant", seed, adminToken));
		assertThat(seeded.path("code").asInt()).as("种子授权：" + seeded.path("msg").asText()).isEqualTo(200);
		assertThat(menuIdsOf(targetRoleId, adminToken)).contains(ownExtraMenuId);
	}

	@Test
	void nonAdminCannotGrantOutsideOwnMenus() {
		Set<Long> before = resetTargetMenus(List.of(ownExtraMenuId));
		Map<String, Object> body = new HashMap<>();
		body.put("roleId", targetRoleId);
		body.put("menuIds", List.of(ownExtraMenuId, outsideMenuId));
		JsonNode r = readBody(post("/system/role/grant", body, granterToken));
		assertThat(r.path("code").asInt()).as(r.path("msg").asText()).isEqualTo(400);
		assertThat(r.path("msg").asText()).contains("超出你的权限或租户套餐");
		assertThat(menuIdsOf(targetRoleId, adminToken)).isEqualTo(before);
	}

	@Test
	void missingMenuIdIsRejected() {
		Set<Long> before = resetTargetMenus(List.of(ownExtraMenuId));
		Map<String, Object> body = new HashMap<>();
		body.put("roleId", targetRoleId);
		body.put("menuIds", List.of(ownExtraMenuId, 999999999999999999L));
		JsonNode r = readBody(post("/system/role/grant", body, granterToken));
		assertThat(r.path("code").asInt()).as(r.path("msg").asText()).isEqualTo(400);
		assertThat(r.path("msg").asText()).contains("不存在或已删除");
		assertThat(menuIdsOf(targetRoleId, adminToken)).isEqualTo(before);
	}

	@Test
	void adminCanGrantExistingMenus() {
		resetTargetMenus(List.of(ownExtraMenuId));
		Map<String, Object> body = new HashMap<>();
		body.put("roleId", targetRoleId);
		body.put("menuIds", List.of(ownExtraMenuId, outsideMenuId, grantMenuId));
		JsonNode r = readBody(post("/system/role/grant", body, adminToken));
		assertThat(r.path("code").asInt()).as(r.path("msg").asText()).isEqualTo(200);
		assertThat(menuIdsOf(targetRoleId, adminToken))
			.contains(ownExtraMenuId, outsideMenuId, grantMenuId);
	}

	@Test
	void nonAdminCanGrantWithinOwnMenus() {
		resetTargetMenus(List.of(ownExtraMenuId));
		Map<String, Object> body = new HashMap<>();
		body.put("roleId", targetRoleId);
		body.put("menuIds", List.of(ownExtraMenuId, grantMenuId));
		JsonNode r = readBody(post("/system/role/grant", body, granterToken));
		assertThat(r.path("code").asInt()).as(r.path("msg").asText()).isEqualTo(200);
		assertThat(menuIdsOf(targetRoleId, adminToken)).contains(ownExtraMenuId, grantMenuId);
	}

	private Set<Long> resetTargetMenus(List<Long> menuIds) {
		Map<String, Object> reset = new HashMap<>();
		reset.put("roleId", targetRoleId);
		reset.put("menuIds", menuIds);
		assertThat(readBody(post("/system/role/grant", reset, adminToken)).path("code").asInt()).isEqualTo(200);
		return menuIdsOf(targetRoleId, adminToken);
	}

	/** 角色页下补齐授权按钮（种子库可能没有 sys:role:grant 菜单）。 */
	private long ensureButton(long parentId, String name, String permission, int sort) {
		Long existing = findMenuIdByPermissionOrNull(permission);
		if (existing != null) {
			return existing;
		}
		Map<String, Object> menu = new HashMap<>();
		menu.put("parentId", parentId);
		menu.put("menuName", name + "-" + TS);
		menu.put("menuType", "F");
		menu.put("permission", permission);
		menu.put("sort", sort);
		menu.put("isHide", 0);
		menu.put("isKeepAlive", 1);
		menu.put("isExternal", 0);
		menu.put("isPublic", 0);
		JsonNode r = readBody(post("/system/menu/submit", menu, adminToken));
		assertThat(r.path("code").asInt()).as("建按钮 " + permission + "：" + r.path("msg").asText()).isEqualTo(200);
		Long id = findMenuIdByPermissionOrNull(permission);
		assertThat(id).as("新建后应能在菜单树找到 " + permission).isNotNull();
		return id;
	}

	private Set<Long> menuIdsOf(long roleId, String token) {
		JsonNode r = readBody(get("/system/role/menu-ids?roleId=" + roleId, token));
		assertThat(r.path("code").asInt()).isEqualTo(200);
		Set<Long> ids = new HashSet<>();
		r.path("data").forEach(n -> ids.add(n.asLong()));
		return ids;
	}

	private String userWithMenus(String roleCode, String username, List<Long> menuIds) {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "集成测试角色授权-" + roleCode);
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
		Long id = findMenuIdByPermissionOrNull(permission);
		assertThat(id).as("菜单树中应存在权限码：" + permission).isNotNull();
		return id;
	}

	private Long findMenuIdByPermissionOrNull(String permission) {
		JsonNode tree = readBody(get("/system/menu/tree", adminToken));
		assertThat(tree.path("code").asInt()).isEqualTo(200);
		return findMenuIdInNodes(tree.path("data"), permission, null);
	}

	private long findMenuIdByPath(String path) {
		JsonNode tree = readBody(get("/system/menu/tree", adminToken));
		assertThat(tree.path("code").asInt()).isEqualTo(200);
		Long id = findMenuIdInNodes(tree.path("data"), null, path);
		assertThat(id).as("菜单树中应存在 path：" + path).isNotNull();
		return id;
	}

	private Long findMenuIdInNodes(JsonNode nodes, String permission, String path) {
		if (nodes == null || !nodes.isArray()) {
			return null;
		}
		for (JsonNode node : nodes) {
			if (permission != null && permission.equals(node.path("permission").asText(null))) {
				return node.path("id").asLong();
			}
			if (path != null && path.equals(node.path("path").asText(null))) {
				return node.path("id").asLong();
			}
			Long found = findMenuIdInNodes(node.path("children"), permission, path);
			if (found != null) {
				return found;
			}
		}
		return null;
	}
}
