package com.mugsun.boot;

import cn.idev.excel.FastExcel;
import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.common.constant.DataScopeConstants;
import com.mugsun.boot.system.excel.SysUserExcel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户写操作的数据范围：角色数据范围为「本部门」的操作员，列表里只看得到本部门的人，
 * 对其他部门的用户调用任何写接口都应失败且数据不变，对本部门的人照常可用。
 * 保存后会落到范围之外（把人建到、调到、导入到别的部门，包括把自己调走）同样拒绝并整体回滚。
 * 解锁接口传锁键时，和传用户 id 一样受数据范围和内置管理员保护约束。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserWriteDataScopeApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final String PWD = NEW_USER_PASSWORD;
	private static final String DEPT_A = "IT写范围部门A-" + TS;
	private static final String DEPT_B = "IT写范围部门B-" + TS;
	private static final String OPERATOR = "it_wds_op_" + TS;
	private static final String WIDE_OPERATOR = "it_wds_wide_" + TS;
	/** 写之前：目标用户不在操作者的数据范围内 */
	private static final String TARGET_OUT_OF_SCOPE = "不在你的数据范围内";
	/** 写之后复核：保存的结果落到范围之外 */
	private static final String RESULT_OUT_OF_SCOPE = "保存后你将看不到这名用户";

	private String adminToken;
	private String operatorToken;
	private String wideToken;
	private long deptAId;
	private long deptBId;
	private long operatorId;
	private long plainRoleId;
	private int seq;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		deptAId = createDept(DEPT_A);
		deptBId = createDept(DEPT_B);

		List<Long> listMenus = menuIds("sys:user:list");
		List<Long> manageMenus = menuIds("sys:user:list", "sys:user:add", "sys:user:edit", "sys:user:remove",
			"sys:user:reset", "sys:user:grant", "sys:user:import");
		long deptRoleId = createRole("it-wds-dept-" + TS, DataScopeConstants.DEPT, manageMenus);
		long wideRoleId = createRole("it-wds-wide-" + TS, DataScopeConstants.ALL, manageMenus);
		// 一个权限很小的角色，用来测「分配角色」
		plainRoleId = createRole("it-wds-plain-" + TS, DataScopeConstants.SELF, listMenus);

		operatorId = createUser(OPERATOR, deptAId, List.of(deptRoleId));
		createUser(WIDE_OPERATOR, deptAId, List.of(wideRoleId));
		operatorToken = loginNewUser(PLATFORM_TENANT, OPERATOR, PWD);
		wideToken = loginNewUser(PLATFORM_TENANT, WIDE_OPERATOR, PWD);
	}

	@Test
	void listShowsOnlyOwnDepartment() {
		String outside = nextName("seeout");
		long outsideId = createUser(outside, deptBId, List.of());

		JsonNode page = readBody(get("/system/user/page?pageNum=1&pageSize=500", operatorToken));
		assertThat(page.path("code").asInt()).isEqualTo(200);
		List<String> usernames = new ArrayList<>();
		page.path("data").path("records").forEach(n -> usernames.add(n.path("username").asText()));
		assertThat(usernames).contains(OPERATOR, WIDE_OPERATOR).doesNotContain(outside, ADMIN_USERNAME);

		JsonNode detail = readBody(get("/system/user/detail?id=" + outsideId, operatorToken));
		assertThat(detail.path("code").asInt()).as("范围外的用户详情也查不到").isNotEqualTo(200);
	}

	@Test
	void writesOnUserOutsideScopeAreRefusedAndChangeNothing() {
		String name = nextName("out");
		long target = createUser(name, deptBId, List.of());
		Map<String, Object> before = snapshot(target);

		assertRejected(get("/system/user/role-ids?userId=" + target, operatorToken), TARGET_OUT_OF_SCOPE);
		assertRejected(post("/system/user/submit", Map.of("id", target, "nickname", "被改名"), operatorToken),
			TARGET_OUT_OF_SCOPE);
		assertRejected(post("/system/user/set-leader?userId=" + target, null, operatorToken), TARGET_OUT_OF_SCOPE);
		assertRejected(post("/system/user/grant", Map.of("userId", target, "roleIds", List.of(plainRoleId)),
			operatorToken), TARGET_OUT_OF_SCOPE);
		assertRejected(post("/system/user/lock", lockBody(target), operatorToken), TARGET_OUT_OF_SCOPE);
		assertRejected(post("/system/user/unlock", Map.of("id", target), operatorToken), TARGET_OUT_OF_SCOPE);
		assertRejected(post("/system/user/status", Map.of("id", target, "status", 0), operatorToken),
			TARGET_OUT_OF_SCOPE);
		ResponseEntity<String> reset = post("/system/user/reset-password", List.of(target), operatorToken);
		assertRejected(reset, TARGET_OUT_OF_SCOPE);
		assertThat(reset.getBody()).as("被拒的重置不应带回口令").doesNotContain("initialPassword");
		assertRejected(post("/system/user/remove", List.of(target), operatorToken), TARGET_OUT_OF_SCOPE);

		assertThat(snapshot(target)).as("被拒的请求不应改动数据").isEqualTo(before);
		assertThat(roleIdsOf(target)).isEmpty();
		assertThat(lockKeys()).doesNotContain(lockKeyOf(name));
		// 没被停用、没被锁定、口令没变：原口令仍能登录
		assertThat(login(PLATFORM_TENANT, name, PWD)).isNotBlank();
	}

	@Test
	void writesInsideScopeStillWork() {
		String name = nextName("in");
		long mate = createUser(name, deptAId, List.of());

		assertOk(get("/system/user/role-ids?userId=" + mate, operatorToken), "查角色");
		assertOk(post("/system/user/submit", Map.of("id", mate, "nickname", "本部门改名"), operatorToken), "改资料");
		assertOk(post("/system/user/set-leader?userId=" + mate, null, operatorToken), "设主管");
		assertOk(post("/system/user/grant", Map.of("userId", mate, "roleIds", List.of(plainRoleId)), operatorToken),
			"分配角色");
		assertOk(post("/system/user/lock", lockBody(mate), operatorToken), "锁定");
		assertOk(post("/system/user/unlock", Map.of("id", mate), operatorToken), "解锁");
		assertOk(post("/system/user/status", Map.of("id", mate, "status", 0), operatorToken), "停用");
		JsonNode reset = readBody(post("/system/user/reset-password", List.of(mate), operatorToken));
		assertThat(reset.path("code").asInt()).as("重置密码：" + reset.path("msg").asText()).isEqualTo(200);
		assertThat(reset.path("data").path(0).path("initialPassword").asText()).isNotBlank();

		Map<String, Object> row = snapshot(mate);
		assertThat(row.get("nickname")).isEqualTo("本部门改名");
		assertThat(((Number) row.get("status")).intValue()).isZero();
		assertThat(((Number) row.get("is_leader")).intValue()).isEqualTo(1);
		assertThat(roleIdsOf(mate)).containsExactly(plainRoleId);

		assertOk(post("/system/user/remove", List.of(mate), operatorToken), "删除");
		assertThat(isAlive(mate)).as("删除后不应还在").isFalse();
	}

	@Test
	void cannotPutUserIntoDepartmentOutsideScope() {
		String inScope = nextName("newa");
		assertOk(post("/system/user/submit", newUserBody(inScope, deptAId), operatorToken), "在本部门新建");
		assertThat(countOf(inScope)).isEqualTo(1);

		String toOther = nextName("newb");
		assertRejected(post("/system/user/submit", newUserBody(toOther, deptBId), operatorToken), RESULT_OUT_OF_SCOPE);
		assertThat(countOf(toOther)).as("被拒的新建应整体回滚").isZero();

		String noDept = nextName("newn");
		assertRejected(post("/system/user/submit", newUserBody(noDept, null), operatorToken), RESULT_OUT_OF_SCOPE);
		assertThat(countOf(noDept)).isZero();

		long mate = userId(inScope);
		Map<String, Object> mateBefore = snapshot(mate);
		assertRejected(post("/system/user/submit", Map.of("id", mate, "deptId", deptBId, "nickname", "顺带改名"),
			operatorToken), RESULT_OUT_OF_SCOPE);
		assertThat(snapshot(mate)).as("调部门被拒时，同一次保存里的其他修改也不生效").isEqualTo(mateBefore);

		Map<String, Object> selfBefore = snapshot(operatorId);
		assertRejected(post("/system/user/submit", Map.of("id", operatorId, "deptId", deptBId), operatorToken),
			RESULT_OUT_OF_SCOPE);
		assertThat(snapshot(operatorId)).as("操作员不能把自己调到别的部门").isEqualTo(selfBefore);
	}

	@Test
	void importFollowsDataScope() {
		String outside = nextName("impout");
		long outsideId = createUser(outside, deptBId, List.of());
		String moved = nextName("impmove");
		long movedId = createUser(moved, deptAId, List.of());
		String mate = nextName("impmate");
		long mateId = createUser(mate, deptAId, List.of());
		String newInScope = nextName("impnewa");
		String newOutside = nextName("impnewb");
		Map<String, Object> outsideBefore = snapshot(outsideId);
		Map<String, Object> movedBefore = snapshot(movedId);

		// 表头占第 1 行，下面依次是第 2–6 行
		List<SysUserExcel> rows = List.of(
			excelRow(newInScope, "导入到本部门", DEPT_A),
			excelRow(newOutside, "导入到别的部门", DEPT_B),
			excelRow(outside, "覆盖别的部门的人", null),
			excelRow(moved, "覆盖时调去别的部门", DEPT_B),
			excelRow(mate, "覆盖本部门的人", null));
		JsonNode r = importUsers(rows, operatorToken);

		assertThat(r.path("code").asInt()).as(r.path("msg").asText()).isEqualTo(200);
		assertThat(r.path("data").path("successCount").asInt()).isEqualTo(2);
		Map<Integer, String> failed = new HashMap<>();
		r.path("data").path("failList").forEach(f -> failed.put(f.path("rowIndex").asInt(), f.path("reason").asText()));
		assertThat(failed).containsOnlyKeys(3, 4, 5);
		assertThat(failed.get(3)).contains(RESULT_OUT_OF_SCOPE);
		assertThat(failed.get(4)).contains(TARGET_OUT_OF_SCOPE);
		assertThat(failed.get(5)).contains(RESULT_OUT_OF_SCOPE);

		assertThat(((Number) snapshot(userId(newInScope)).get("dept_id")).longValue()).isEqualTo(deptAId);
		assertThat(countOf(newOutside)).as("被拒的导入行不应留下用户").isZero();
		assertThat(snapshot(outsideId)).isEqualTo(outsideBefore);
		assertThat(snapshot(movedId)).as("覆盖被拒时这一行整体回滚").isEqualTo(movedBefore);
		assertThat(snapshot(mateId).get("nickname")).isEqualTo("覆盖本部门的人");
	}

	@Test
	void unlockByLockKeyGetsTheSameChecksAsUnlockById() {
		String outside = nextName("lockout");
		long outsideId = createUser(outside, deptBId, List.of());
		assertOk(post("/system/user/lock", lockBody(outsideId), adminToken), "管理员锁定别的部门的人");

		assertRejected(post("/system/user/unlock", Map.of("lockKey", lockKeyOf(outside)), operatorToken),
			TARGET_OUT_OF_SCOPE);
		// 演示模式的锁键在账号后面带来源地址，同样要认出是哪个账号
		assertRejected(post("/system/user/unlock", Map.of("lockKey", lockKeyOf(outside) + "@ip:10.0.0.1"),
			operatorToken), TARGET_OUT_OF_SCOPE);
		assertThat(lockKeys()).as("被拒的解锁不应清掉锁").contains(lockKeyOf(outside));

		// 数据范围是全部、但不是平台超管的操作员：按 id 解锁管理员本来就被拒，传锁键也一样
		assertRejected(post("/system/user/unlock", Map.of("id", userId(ADMIN_USERNAME)), wideToken), "内置管理员账号");
		assertRejected(post("/system/user/unlock", Map.of("lockKey", lockKeyOf(ADMIN_USERNAME)), wideToken),
			"内置管理员账号");

		String mate = nextName("lockin");
		long mateId = createUser(mate, deptAId, List.of());
		assertOk(post("/system/user/lock", lockBody(mateId), operatorToken), "锁定本部门的人");
		assertThat(lockKeys()).contains(lockKeyOf(mate));
		assertOk(post("/system/user/unlock", Map.of("lockKey", lockKeyOf(mate)), operatorToken), "按锁键解锁本部门的人");
		assertThat(lockKeys()).doesNotContain(lockKeyOf(mate));

		// 没有对应用户的锁键（有人拿不存在的账号试密码留下的）可以直接清
		assertOk(post("/system/user/unlock", Map.of("lockKey", lockKeyOf("it_wds_nobody_" + TS)), operatorToken),
			"清不存在账号的锁");

		assertOk(post("/system/user/unlock", Map.of("id", outsideId), adminToken), "管理员解锁");
		assertThat(lockKeys()).doesNotContain(lockKeyOf(outside));
	}

	@Test
	void operatorWithAllDataScopeManagesEveryDepartment() {
		String name = nextName("wide");
		long user = createUser(name, deptBId, List.of());

		assertOk(post("/system/user/submit", Map.of("id", user, "nickname", "全部范围可改"), wideToken), "改别的部门的人");
		assertOk(post("/system/user/submit", Map.of("id", user, "deptId", deptAId), wideToken), "调部门");
		Map<String, Object> row = snapshot(user);
		assertThat(row.get("nickname")).isEqualTo("全部范围可改");
		assertThat(((Number) row.get("dept_id")).longValue()).isEqualTo(deptAId);

		String noDept = nextName("widenew");
		assertOk(post("/system/user/submit", newUserBody(noDept, null), wideToken), "新建不挂部门的人");
		assertThat(countOf(noDept)).isEqualTo(1);

		assertOk(post("/system/user/remove", List.of(user), wideToken), "删除");
		assertThat(isAlive(user)).as("删除后不应还在").isFalse();
	}

	// ---------- 辅助 ----------

	private String nextName(String tag) {
		return "it_wds_" + tag + "_" + TS + "_" + (++seq);
	}

	private String lockKeyOf(String username) {
		return PLATFORM_TENANT + ":" + username;
	}

	private Map<String, Object> lockBody(long userId) {
		return Map.of("id", userId, "minutes", 5, "reason", "集成测试锁定");
	}

	private Map<String, Object> newUserBody(String username, Long deptId) {
		Map<String, Object> user = new HashMap<>();
		user.put("username", username);
		user.put("nickname", "集成测试-" + username);
		user.put("password", PWD);
		user.put("status", 1);
		if (deptId != null) {
			user.put("deptId", deptId);
		}
		return user;
	}

	/** 平台管理员建用户，返回用户 id */
	private long createUser(String username, long deptId, List<Long> roleIds) {
		Map<String, Object> user = newUserBody(username, deptId);
		user.put("roleIds", roleIds);
		assertOk(post("/system/user/submit", user, adminToken), "建用户 " + username);
		return userId(username);
	}

	private long createDept(String deptName) {
		Map<String, Object> dept = new HashMap<>();
		dept.put("deptName", deptName);
		dept.put("fullName", deptName + "（全称）");
		dept.put("category", "dept");
		dept.put("parentId", 0);
		dept.put("sort", 91);
		assertOk(post("/system/dept/submit", dept, adminToken), "建部门 " + deptName);
		return jdbc.queryForObject(
			"select id from sys_dept where dept_name = ? and tenant_id = ? and coalesce(is_deleted, 0) = 0",
			Long.class, deptName, PLATFORM_TENANT);
	}

	private long createRole(String code, int dataScope, List<Long> menuIds) {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "集成测试-" + code);
		role.put("roleCode", code);
		role.put("sort", 91);
		role.put("dataScope", dataScope);
		assertOk(post("/system/role/submit", role, adminToken), "建角色 " + code);
		long id = jdbc.queryForObject(
			"select id from sys_role where role_code = ? and tenant_id = ? and coalesce(is_deleted, 0) = 0",
			Long.class, code, PLATFORM_TENANT);
		Map<String, Object> grant = new HashMap<>();
		grant.put("roleId", id);
		grant.put("menuIds", menuIds);
		assertOk(post("/system/role/grant", grant, adminToken), "角色授权菜单 " + code);
		return id;
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

	private long userId(String username) {
		return jdbc.queryForObject(
			"select id from sys_user where username = ? and tenant_id = ? and coalesce(is_deleted, 0) = 0",
			Long.class, username, PLATFORM_TENANT);
	}

	/** 这个账号在库里的行数，含已删除的：回滚干净时是 0 */
	private int countOf(String username) {
		return jdbc.queryForObject("select count(*) from sys_user where username = ? and tenant_id = ?",
			Integer.class, username, PLATFORM_TENANT);
	}

	private boolean isAlive(long userId) {
		return jdbc.queryForObject("select count(*) from sys_user where id = ? and coalesce(is_deleted, 0) = 0",
			Integer.class, userId) > 0;
	}

	/** 写接口可能改到的列，用来比较请求前后有没有变化 */
	private Map<String, Object> snapshot(long userId) {
		return jdbc.queryForMap(
			"select nickname, status, is_leader, dept_id, password, must_change_password, coalesce(is_deleted, 0) as is_deleted"
				+ " from sys_user where id = ?", userId);
	}

	private List<Long> roleIdsOf(long userId) {
		return jdbc.queryForList(
			"select role_id from sys_user_role where user_id = ? and coalesce(is_deleted, 0) = 0", Long.class, userId);
	}

	/** 当前租户仍在锁定中的锁键（管理员视角） */
	private List<String> lockKeys() {
		JsonNode body = readBody(get("/system/user/locks", adminToken));
		assertThat(body.path("code").asInt()).as(body.path("msg").asText()).isEqualTo(200);
		List<String> keys = new ArrayList<>();
		body.path("data").forEach(row -> keys.add(row.path("lockKey").asText()));
		return keys;
	}

	private SysUserExcel excelRow(String username, String nickname, String deptName) {
		SysUserExcel row = new SysUserExcel();
		row.setUsername(username);
		row.setNickname(nickname);
		row.setDeptName(deptName);
		return row;
	}

	/** 以覆盖模式导入用户表格 */
	private JsonNode importUsers(List<SysUserExcel> rows, String token) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		FastExcel.write(out, SysUserExcel.class).sheet("用户导入").doWrite(rows);
		HttpHeaders headers = authHeaders(token);
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", new ByteArrayResource(out.toByteArray()) {
			@Override
			public String getFilename() {
				return "users.xlsx";
			}
		});
		body.add("updateSupport", "true");
		ResponseEntity<String> resp = rest.exchange("/system/user/import", HttpMethod.POST,
			new HttpEntity<>(body, headers), String.class);
		return readBody(resp);
	}

	private void assertOk(ResponseEntity<String> response, String what) {
		JsonNode body = readBody(response);
		assertThat(body.path("code").asInt()).as(what + "：" + body.path("msg").asText()).isEqualTo(200);
	}

	private void assertRejected(ResponseEntity<String> response, String msgPart) {
		JsonNode body = readBody(response);
		assertThat(body.path("code").asInt()).as(body.path("msg").asText()).isNotEqualTo(200);
		assertThat(body.path("msg").asText()).contains(msgPart);
	}
}
