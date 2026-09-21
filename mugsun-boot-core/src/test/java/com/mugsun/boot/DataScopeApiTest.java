package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据权限引擎（G73）回归：/system/user/page 按角色数据范围注入 OR 并集行级条件。
 * 前置：经 admin API 建两个部门 + 五种数据范围角色（1全部/2本部门/4仅本人/5自定义部门/无范围）+ 用户并授权限码与角色，
 * 再以各用户真实登录分页查询。
 * <p>断言矩阵：全部→见全量；本部门→仅本部门行；仅本人→仅自身一行；仅本人∪自定义部门→OR 并集；
 * 有读权限码但角色无任何数据范围→fail-closed 0 行（未登录 401 / 无读码 403 已由 PermissionGuardApiTest 覆盖）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataScopeApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final String PWD = "123456";

	// 部门与角色码（时间戳后缀唯一化，容器一次性不清理）
	private static final String DEPT_A_NAME = "IT数据权限部门A-" + TS;
	private static final String DEPT_B_NAME = "IT数据权限部门B-" + TS;
	private static final String ROLE_ALL = "it-ds-all-" + TS;
	private static final String ROLE_DEPT = "it-ds-dept-" + TS;
	private static final String ROLE_SELF = "it-ds-self-" + TS;
	private static final String ROLE_CUSTOM = "it-ds-cust-" + TS;
	private static final String ROLE_NONE = "it-ds-none-" + TS;

	// 观察员账号（各绑不同数据范围角色）
	private static final String VIEW_ALL = "it-ds-va-" + TS;
	private static final String VIEW_DEPT = "it-ds-vd-" + TS;
	private static final String VIEW_SELF = "it-ds-vs-" + TS;
	private static final String VIEW_BOTH = "it-ds-vb-" + TS;
	private static final String VIEW_NONE = "it-ds-vn-" + TS;
	// 纯数据行账号（不授角色）
	private static final String WORKER_A1 = "it-ds-wa1-" + TS;
	private static final String WORKER_A2 = "it-ds-wa2-" + TS;
	private static final String WORKER_B1 = "it-ds-wb1-" + TS;

	private String adminToken;
	private long deptAId;
	private long deptBId;
	private String tokenAll;
	private String tokenDept;
	private String tokenSelf;
	private String tokenBoth;
	private String tokenNone;

	@BeforeAll
	void setupScopeFixture() {
		adminToken = loginAdmin();
		long userListMenuId = findMenuIdByPermission("sys:user:list");

		// 1) 两个顶级部门（平台租户内）
		deptAId = createDept(DEPT_A_NAME);
		deptBId = createDept(DEPT_B_NAME);

		// 2) 五种数据范围角色，均授 sys:user:list 读码（角色数据范围与读码解耦，范围由引擎注入）
		long roleAllId = createRole(ROLE_ALL, 1, null);
		long roleDeptId = createRole(ROLE_DEPT, 2, null);
		long roleSelfId = createRole(ROLE_SELF, 4, null);
		long roleCustomId = createRole(ROLE_CUSTOM, 5, List.of(deptAId));
		// 无范围角色：data_scope=6（自定义 SQL）但不配 SQL——引擎拼不出任何条件即 fail-closed
		// （不能用 dataScope=null：sys_role.data_scope 为 NOT NULL 列，null 插入违反约束）
		long roleNoneId = createRole(ROLE_NONE, 6, null);
		for (long roleId : new long[]{roleAllId, roleDeptId, roleSelfId, roleCustomId, roleNoneId}) {
			grantRoleMenus(roleId, userListMenuId);
		}

		// 3) 观察员用户：A 部门 {全部、本部门、无范围}，B 部门 {仅本人、仅本人+自定义A部门}
		createUserWithRoles(VIEW_ALL, deptAId, List.of(roleAllId));
		createUserWithRoles(VIEW_DEPT, deptAId, List.of(roleDeptId));
		createUserWithRoles(VIEW_NONE, deptAId, List.of(roleNoneId));
		createUserWithRoles(VIEW_SELF, deptBId, List.of(roleSelfId));
		createUserWithRoles(VIEW_BOTH, deptBId, List.of(roleSelfId, roleCustomId));
		// 4) 纯数据行（A 部门两人、B 部门一人）
		createUserWithRoles(WORKER_A1, deptAId, null);
		createUserWithRoles(WORKER_A2, deptAId, null);
		createUserWithRoles(WORKER_B1, deptBId, null);

		// 5) 各观察员真实登录（同前端链路）
		tokenAll = login(PLATFORM_TENANT, VIEW_ALL, PWD);
		tokenDept = login(PLATFORM_TENANT, VIEW_DEPT, PWD);
		tokenSelf = login(PLATFORM_TENANT, VIEW_SELF, PWD);
		tokenBoth = login(PLATFORM_TENANT, VIEW_BOTH, PWD);
		tokenNone = login(PLATFORM_TENANT, VIEW_NONE, PWD);
	}

	@Test
	void allScopeSeesEntireTenant() {
		JsonNode r = readBody(get("/system/user/page?pageNum=1&pageSize=200", tokenAll));
		assertThat(r.path("code").asInt()).isEqualTo(200);
		List<String> usernames = usernamesOf(r);
		// 全部范围：本测试全部账号 + 平台超管均可见（租户隔离仍生效，他租户用户不出现）
		assertThat(usernames).contains(ADMIN_USERNAME,
			VIEW_ALL, VIEW_DEPT, VIEW_SELF, VIEW_BOTH, VIEW_NONE, WORKER_A1, WORKER_A2, WORKER_B1);
	}

	@Test
	void deptScopeSeesOnlyOwnDept() {
		JsonNode r = readBody(get("/system/user/page?pageNum=1&pageSize=200", tokenDept));
		assertThat(r.path("code").asInt()).isEqualTo(200);
		JsonNode records = r.path("data").path("records");
		// 本部门范围：deptA 恰有 5 人（三观察员 + 两数据行），无第二页截断风险
		assertThat(records.size()).as("本部门范围行数").isEqualTo(5);
		for (JsonNode record : records) {
			assertThat(record.path("deptId").asLong()).as("每行均属本部门").isEqualTo(deptAId);
		}
		List<String> usernames = usernamesOf(r);
		assertThat(usernames).containsExactlyInAnyOrder(VIEW_ALL, VIEW_DEPT, VIEW_NONE, WORKER_A1, WORKER_A2);
		// 反向断言：B 部门账号与无部门超管绝不出现
		assertThat(usernames).doesNotContain(WORKER_B1, VIEW_SELF, VIEW_BOTH, ADMIN_USERNAME);
	}

	@Test
	void selfScopeSeesOnlySelf() {
		JsonNode r = readBody(get("/system/user/page?pageNum=1&pageSize=200", tokenSelf));
		assertThat(r.path("code").asInt()).isEqualTo(200);
		JsonNode records = r.path("data").path("records");
		assertThat(records.size()).as("仅本人范围恰一行").isEqualTo(1);
		assertThat(records.get(0).path("username").asText()).isEqualTo(VIEW_SELF);
	}

	@Test
	void selfUnionCustomDeptSeesBoth() {
		// OR 并集：仅本人（deptB）∪ 自定义部门（deptA）= 本人 + deptA 全员，deptB 其他人不可见
		JsonNode r = readBody(get("/system/user/page?pageNum=1&pageSize=200", tokenBoth));
		assertThat(r.path("code").asInt()).isEqualTo(200);
		List<String> usernames = usernamesOf(r);
		assertThat(usernames).containsExactlyInAnyOrder(
			VIEW_BOTH, VIEW_ALL, VIEW_DEPT, VIEW_NONE, WORKER_A1, WORKER_A2);
		// 反向断言：同部门（deptB）的其他人不因「本人规则」外溢可见
		assertThat(usernames).doesNotContain(WORKER_B1, VIEW_SELF, ADMIN_USERNAME);
	}

	@Test
	void noScopeRoleFailsClosed() {
		// 持读码但角色为自定义 SQL 范围且未配 SQL（解析不出任何范围条件）→ 引擎注入 1=0，绝不返全量
		JsonNode r = readBody(get("/system/user/page?pageNum=1&pageSize=200", tokenNone));
		assertThat(r.path("code").asInt()).isEqualTo(200);
		assertThat(r.path("data").path("total").asLong()).as("fail-closed 总条数").isEqualTo(0);
		assertThat(r.path("data").path("records").size()).isEqualTo(0);
	}

	// ---------- 前置构造辅助（均走 admin 真实 API） ----------

	/** 菜单树按权限码递归取菜单主键（授权角色菜单的入参） */
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

	private long createDept(String deptName) {
		Map<String, Object> dept = new HashMap<>();
		dept.put("deptName", deptName);
		dept.put("parentId", 0);
		dept.put("sort", 90);
		JsonNode r = readBody(post("/system/dept/submit", dept, adminToken));
		assertThat(r.path("code").asInt()).as("建部门：" + r.path("msg").asText()).isEqualTo(200);
		JsonNode options = readBody(get("/system/dept/select", adminToken));
		for (JsonNode option : options.path("data")) {
			if (deptName.equals(option.path("label").asText())) {
				return option.path("value").asLong();
			}
		}
		throw new IllegalStateException("部门下拉中未找到：" + deptName);
	}

	/** 建角色（dataScope 可空 = 无范围角色；deptIds 为 scope=5 自定义部门集合） */
	private long createRole(String roleCode, Integer dataScope, List<Long> deptIds) {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "集成测试数据范围-" + roleCode);
		role.put("roleCode", roleCode);
		role.put("sort", 90);
		if (dataScope != null) {
			role.put("dataScope", dataScope);
		}
		if (deptIds != null) {
			role.put("deptIds", deptIds);
		}
		JsonNode r = readBody(post("/system/role/submit", role, adminToken));
		assertThat(r.path("code").asInt()).as("建角色：" + r.path("msg").asText()).isEqualTo(200);
		return findRoleIdByCode(roleCode);
	}

	private long findRoleIdByCode(String roleCode) {
		JsonNode page = readBody(get("/system/role/page?pageNum=1&pageSize=200&roleCode=" + roleCode, adminToken));
		for (JsonNode record : page.path("data").path("records")) {
			if (roleCode.equals(record.path("roleCode").asText())) {
				return record.path("id").asLong();
			}
		}
		throw new IllegalStateException("角色分页中未找到：" + roleCode);
	}

	private void grantRoleMenus(long roleId, long menuId) {
		Map<String, Object> grant = new HashMap<>();
		grant.put("roleId", roleId);
		grant.put("menuIds", List.of(menuId));
		JsonNode r = readBody(post("/system/role/grant", grant, adminToken));
		assertThat(r.path("code").asInt()).as("角色授菜单：" + r.path("msg").asText()).isEqualTo(200);
	}

	/** 建用户（可选同步挂角色；roleIds 为 null 时纯数据行） */
	private void createUserWithRoles(String username, long deptId, List<Long> roleIds) {
		Map<String, Object> user = new HashMap<>();
		user.put("username", username);
		user.put("nickname", "集成测试-" + username);
		user.put("password", PWD);
		user.put("status", 1);
		user.put("deptId", deptId);
		JsonNode r = readBody(post("/system/user/submit", user, adminToken));
		assertThat(r.path("code").asInt()).as("建用户：" + r.path("msg").asText()).isEqualTo(200);
		if (roleIds == null || roleIds.isEmpty()) {
			return;
		}
		long userId = findUserIdByUsername(username);
		Map<String, Object> grant = new HashMap<>();
		grant.put("userId", userId);
		grant.put("roleIds", roleIds);
		JsonNode grantResp = readBody(post("/system/user/grant", grant, adminToken));
		assertThat(grantResp.path("code").asInt()).as("用户授权：" + grantResp.path("msg").asText()).isEqualTo(200);
	}

	private long findUserIdByUsername(String username) {
		JsonNode page = readBody(get("/system/user/page?pageNum=1&pageSize=200&username=" + username, adminToken));
		for (JsonNode record : page.path("data").path("records")) {
			if (username.equals(record.path("username").asText())) {
				return record.path("id").asLong();
			}
		}
		throw new IllegalStateException("用户分页中未找到：" + username);
	}

	private List<String> usernamesOf(JsonNode pageBody) {
		List<String> usernames = new ArrayList<>();
		pageBody.path("data").path("records").forEach(n -> usernames.add(n.path("username").asText()));
		return usernames;
	}
}
