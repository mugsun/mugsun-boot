package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.common.constant.DataScopeConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 委派出去的用户管理权不能拿来提权：只有用户管理权限的操作者，不能给人挂菜单或数据范围比自己大的角色、
 * 内置管理员角色，不能改自己和管理员账号的角色；用户保存和「分配角色」两条路径同一口径。
 * <p>操作者的数据范围是「本部门及以下」，被操作的用户都建在操作者的部门里：
 * 范围外的用户会先被数据范围拦下（见 {@link UserWriteDataScopeApiTest}），轮不到这里要测的角色检查。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserRoleAssignGuardApiTest extends AbstractIntegrationTest {

	private static final String PASSWORD = "123456";
	private static final String ROLE_PAGE = "/system/role/page?pageNum=1&pageSize=500";
	private static final String USER_PAGE = "/system/user/page?pageNum=1&pageSize=500";

	private String adminToken;
	private String operatorToken;
	private long deptId;
	private long operatorId;
	private long operatorRoleId;
	private long smallRoleId;
	private long bigRoleId;
	private long wideRoleId;
	private long adminRoleId;
	private long targetId;
	private long grantedId;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		deptId = createDept("集成测试-角色分配部门");
		List<Long> listMenus = menuIds("sys:user:list");
		List<Long> operatorMenus = new ArrayList<>(listMenus);
		operatorMenus.addAll(menuIds("sys:user:add", "sys:user:edit", "sys:user:grant"));
		List<Long> bigMenus = new ArrayList<>(listMenus);
		bigMenus.addAll(menuIds("sys:user:remove"));

		operatorRoleId = createRole("it-assign-op", DataScopeConstants.DEPT_AND_CHILD, operatorMenus);
		smallRoleId = createRole("it-assign-small", DataScopeConstants.DEPT_AND_CHILD, listMenus);
		bigRoleId = createRole("it-assign-big", DataScopeConstants.DEPT_AND_CHILD, bigMenus);
		wideRoleId = createRole("it-assign-wide", DataScopeConstants.ALL, listMenus);
		adminRoleId = idOf(ROLE_PAGE, "roleCode", "admin");

		operatorId = createUser("it_assign_op", List.of(operatorRoleId));
		targetId = createUser("it_assign_target", List.of());
		grantedId = createUser("it_assign_granted", List.of());
		operatorToken = login(PLATFORM_TENANT, "it_assign_op", PASSWORD);
	}

	@Test
	void cannotGrantRoleWithMoreMenus() {
		assertRejected(grant(targetId, List.of(bigRoleId)), "菜单权限超出");
		assertThat(roleIdsOf(targetId)).isEmpty();
	}

	@Test
	void cannotGrantRoleWithWiderDataScope() {
		assertRejected(grant(targetId, List.of(wideRoleId)), "数据范围超出");
		assertThat(roleIdsOf(targetId)).isEmpty();
	}

	@Test
	void cannotGrantAdminRole() {
		assertRejected(grant(targetId, List.of(adminRoleId)), "内置管理员角色");
		assertThat(roleIdsOf(targetId)).isEmpty();
	}

	@Test
	void cannotChangeOwnRoles() {
		assertRejected(grant(operatorId, List.of(operatorRoleId, smallRoleId)), "不能修改自己的角色");
		assertThat(roleIdsOf(operatorId)).containsExactly(operatorRoleId);
	}

	@Test
	void cannotClearAdminAccountRoles() {
		// 管理员账号在操作者的数据范围内时，由角色分配这一层拦下
		long adminInScopeId = createUser("it_assign_admin2", List.of(adminRoleId));
		try {
			// roleIds 缺省即清空全部角色
			assertRejected(grant(adminInScopeId, null), "管理员账号");
			assertThat(roleIdsOf(adminInScopeId)).containsExactly(adminRoleId);
		} finally {
			// 用完收回管理员角色，不给后面的测试多留一个超管
			Map<String, Object> revoke = new HashMap<>();
			revoke.put("userId", adminInScopeId);
			revoke.put("roleIds", List.of());
			post("/system/user/grant", revoke, adminToken);
		}

		// 内置管理员不在操作者的部门里，先被数据范围拦下
		long adminId = idOf(USER_PAGE, "username", ADMIN_USERNAME);
		assertRejected(grant(adminId, null), "不在你的数据范围内");
		assertThat(roleIdsOf(adminId)).contains(adminRoleId);
	}

	@Test
	void userSubmitCannotSmuggleRoles() {
		Map<String, Object> create = userBody("it_assign_smuggle", List.of(bigRoleId));
		create.put("password", PASSWORD);
		assertRejected(post("/system/user/submit", create, operatorToken), "菜单权限超出");
		assertThat(jdbc.queryForObject("select count(*) from sys_user where username = ?", Integer.class,
			"it_assign_smuggle")).isZero();

		Map<String, Object> edit = userBody("it_assign_target", List.of(adminRoleId));
		edit.put("id", targetId);
		assertRejected(post("/system/user/submit", edit, operatorToken), "内置管理员角色");
		assertThat(roleIdsOf(targetId)).isEmpty();
	}

	@Test
	void canAssignRoleWithinOwnPermissions() {
		assertOk(grant(grantedId, List.of(smallRoleId)), "分配子集角色");
		assertThat(roleIdsOf(grantedId)).containsExactly(smallRoleId);

		Map<String, Object> edit = userBody("it_assign_granted", List.of(smallRoleId));
		edit.put("id", grantedId);
		edit.put("nickname", "集成测试-只改昵称");
		assertOk(post("/system/user/submit", edit, operatorToken), "角色不变时只改资料");
		assertThat(roleIdsOf(grantedId)).containsExactly(smallRoleId);
	}

	@Test
	void roleSelectHidesAdminFromRestrictedOperators() {
		assertThat(selectValues(operatorToken)).contains(smallRoleId).doesNotContain(adminRoleId);
		assertThat(selectValues(adminToken)).contains(adminRoleId);

		String noPermToken = login(PLATFORM_TENANT, "it_assign_target", PASSWORD);
		assertThat(readBody(get("/system/role/select", noPermToken)).path("code").asInt()).isEqualTo(403);
	}

	private List<Long> selectValues(String token) {
		JsonNode body = readBody(get("/system/role/select", token));
		assertThat(body.path("code").asInt()).as(body.toString()).isEqualTo(200);
		List<Long> values = new ArrayList<>();
		body.path("data").forEach(option -> values.add(option.path("value").asLong()));
		return values;
	}

	private ResponseEntity<String> grant(long userId, List<Long> roleIds) {
		Map<String, Object> body = new HashMap<>();
		body.put("userId", userId);
		body.put("roleIds", roleIds);
		return post("/system/user/grant", body, operatorToken);
	}

	private Map<String, Object> userBody(String username, List<Long> roleIds) {
		Map<String, Object> user = new HashMap<>();
		user.put("username", username);
		user.put("nickname", "集成测试-" + username);
		user.put("status", 1);
		user.put("deptId", deptId);
		user.put("roleIds", roleIds);
		return user;
	}

	private long createDept(String deptName) {
		Map<String, Object> dept = new HashMap<>();
		dept.put("deptName", deptName);
		dept.put("fullName", deptName);
		dept.put("category", "dept");
		dept.put("parentId", 0);
		dept.put("sort", 99);
		assertOk(post("/system/dept/submit", dept, adminToken), "建部门 " + deptName);
		return jdbc.queryForObject(
			"select id from sys_dept where dept_name = ? and tenant_id = ? and coalesce(is_deleted, 0) = 0",
			Long.class, deptName, PLATFORM_TENANT);
	}

	private long createRole(String code, int dataScope, List<Long> menuIds) {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "集成测试-" + code);
		role.put("roleCode", code);
		role.put("sort", 99);
		role.put("dataScope", dataScope);
		assertOk(post("/system/role/submit", role, adminToken), "建角色 " + code);
		long id = idOf(ROLE_PAGE, "roleCode", code);
		Map<String, Object> grant = new HashMap<>();
		grant.put("roleId", id);
		grant.put("menuIds", menuIds);
		assertOk(post("/system/role/grant", grant, adminToken), "角色授权菜单 " + code);
		return id;
	}

	private long createUser(String username, List<Long> roleIds) {
		Map<String, Object> user = userBody(username, roleIds);
		user.put("password", PASSWORD);
		assertOk(post("/system/user/submit", user, adminToken), "建用户 " + username);
		return idOf(USER_PAGE, "username", username);
	}

	private long idOf(String pageUrl, String field, String value) {
		JsonNode page = readBody(get(pageUrl, adminToken));
		for (JsonNode record : page.path("data").path("records")) {
			if (value.equals(record.path(field).asText())) {
				return record.path("id").asLong();
			}
		}
		throw new IllegalStateException("分页中未找到 " + field + "=" + value);
	}

	private List<Long> menuIds(String... permissions) {
		List<Long> ids = new ArrayList<>();
		for (String permission : permissions) {
			List<Long> found = jdbc.queryForList(
				"select id from sys_menu where permission = ? and coalesce(is_deleted, 0) = 0", Long.class, permission);
			assertThat(found).as("菜单权限码 " + permission).isNotEmpty();
			ids.addAll(found);
		}
		return ids;
	}

	private List<Long> roleIdsOf(long userId) {
		return jdbc.queryForList(
			"select role_id from sys_user_role where user_id = ? and coalesce(is_deleted, 0) = 0", Long.class, userId);
	}

	private void assertOk(ResponseEntity<String> response, String what) {
		JsonNode body = readBody(response);
		assertThat(body.path("code").asInt()).as(what + "：" + body.path("msg").asText()).isEqualTo(200);
	}

	private void assertRejected(ResponseEntity<String> response, String msgPart) {
		JsonNode body = readBody(response);
		assertThat(body.path("code").asInt()).as(body.toString()).isNotEqualTo(200);
		assertThat(body.path("msg").asText()).contains(msgPart);
	}
}
