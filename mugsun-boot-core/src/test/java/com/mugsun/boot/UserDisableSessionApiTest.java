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
 * 停用用户即回收会话：停用状态只在登录时检查，所以把人停用的每条路径都要踢下线。
 * 列表里的停用开关、编辑弹窗里的状态开关、导入覆盖三条路径结果一致；
 * 没有停用的保存、被拒后回滚的保存不踢人；编辑和导入不能把自己停用。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserDisableSessionApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final String OPERATOR = "it_dis_op_" + TS;
	private static final String SELF_DISABLE = "不能停用当前登录的账号";

	private String adminToken;
	private String operatorToken;
	private long operatorId;
	private int seq;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		// 能编辑、能导入用户的普通操作员（不是平台超管，数据范围是全部）
		long roleId = createRole("it-dis-op-" + TS, menuIds("sys:user:list", "sys:user:edit", "sys:user:import"));
		operatorId = createUser(OPERATOR, List.of(roleId));
		operatorToken = loginNewUser(PLATFORM_TENANT, OPERATOR, NEW_USER_PASSWORD);
	}

	@Test
	void disablingInEditDialogKicksTheUserOut() {
		String name = nextName("edit");
		long id = createUser(name, List.of());
		String token = loginNewUser(PLATFORM_TENANT, name, NEW_USER_PASSWORD);
		assertThat(httpStatusOfAuthInfo(token)).isEqualTo(200);

		assertOk(post("/system/user/submit", Map.of("id", id, "nickname", "编辑里停用", "status", 0), operatorToken),
			"编辑里停用");

		assertThat(statusOf(id)).isZero();
		assertThat(httpStatusOfAuthInfo(token)).as("停用后原来的登录应失效").isEqualTo(401);
		assertThat(tryLogin(name).path("msg").asText()).contains("账号已停用");
	}

	@Test
	void disablingByImportOverwriteKicksTheUserOut() {
		String name = nextName("imp");
		long id = createUser(name, List.of());
		String token = loginNewUser(PLATFORM_TENANT, name, NEW_USER_PASSWORD);
		assertThat(httpStatusOfAuthInfo(token)).isEqualTo(200);

		JsonNode r = importUsers(List.of(excelRow(name, "导入里停用", 0)), operatorToken);
		assertThat(r.path("code").asInt()).as(r.path("msg").asText()).isEqualTo(200);
		assertThat(r.path("data").path("successCount").asInt()).isEqualTo(1);

		assertThat(statusOf(id)).isZero();
		assertThat(httpStatusOfAuthInfo(token)).as("停用后原来的登录应失效").isEqualTo(401);
		assertThat(tryLogin(name).path("msg").asText()).contains("账号已停用");
	}

	@Test
	void disablingByStatusSwitchKicksTheUserOut() {
		String name = nextName("switch");
		long id = createUser(name, List.of());
		String token = loginNewUser(PLATFORM_TENANT, name, NEW_USER_PASSWORD);
		assertThat(httpStatusOfAuthInfo(token)).isEqualTo(200);

		assertOk(post("/system/user/status", Map.of("id", id, "status", 0), operatorToken), "列表里停用");

		assertThat(statusOf(id)).isZero();
		assertThat(httpStatusOfAuthInfo(token)).as("停用后原来的登录应失效").isEqualTo(401);
	}

	@Test
	void savesThatDoNotDisableLeaveTheSessionAlone() {
		String name = nextName("keep");
		long id = createUser(name, List.of());
		String token = loginNewUser(PLATFORM_TENANT, name, NEW_USER_PASSWORD);

		assertOk(post("/system/user/submit", Map.of("id", id, "nickname", "只改昵称", "status", 1), operatorToken),
			"编辑但不停用");
		// 导入覆盖不填状态列：状态不动
		JsonNode r = importUsers(List.of(excelRow(name, "导入只改昵称", null)), operatorToken);
		assertThat(r.path("data").path("successCount").asInt()).as(r.toString()).isEqualTo(1);

		assertThat(statusOf(id)).isEqualTo(1);
		assertThat(httpStatusOfAuthInfo(token)).as("没停用就不该踢人").isEqualTo(200);
	}

	@Test
	void rejectedSaveRollsBackAndDoesNotKick() {
		String name = nextName("rollback");
		long id = createUser(name, List.of());
		String token = loginNewUser(PLATFORM_TENANT, name, NEW_USER_PASSWORD);

		// 同一次保存里既停用又带了不存在的角色：角色校验在写库之后，整个保存回滚
		Map<String, Object> edit = new HashMap<>();
		edit.put("id", id);
		edit.put("status", 0);
		edit.put("roleIds", List.of(987654321012345L));
		assertRejected(post("/system/user/submit", edit, adminToken), "有角色不存在");

		assertThat(statusOf(id)).as("被拒的保存整体回滚").isEqualTo(1);
		assertThat(httpStatusOfAuthInfo(token)).as("回滚了就不该踢人").isEqualTo(200);
	}

	@Test
	void cannotDisableOneselfInEditOrImport() {
		assertRejected(post("/system/user/submit", Map.of("id", operatorId, "status", 0), operatorToken), SELF_DISABLE);

		// 导入表格里带了自己这一行且状态是停用：这一行失败，其他行照常
		String other = nextName("mate");
		long otherId = createUser(other, List.of());
		JsonNode r = importUsers(List.of(excelRow(OPERATOR, null, 0), excelRow(other, "同一张表里的别人", null)),
			operatorToken);
		assertThat(r.path("code").asInt()).as(r.path("msg").asText()).isEqualTo(200);
		assertThat(r.path("data").path("successCount").asInt()).isEqualTo(1);
		JsonNode failed = r.path("data").path("failList");
		assertThat(failed.size()).isEqualTo(1);
		// 表头占第 1 行，自己那一行是第 2 行
		assertThat(failed.path(0).path("rowIndex").asInt()).isEqualTo(2);
		assertThat(failed.path(0).path("reason").asText()).contains(SELF_DISABLE);
		assertThat(nicknameOf(otherId)).isEqualTo("同一张表里的别人");

		assertThat(statusOf(operatorId)).isEqualTo(1);
		assertThat(httpStatusOfAuthInfo(operatorToken)).isEqualTo(200);
		// 改自己的其他资料不受影响
		assertOk(post("/system/user/submit", Map.of("id", operatorId, "nickname", "改自己的昵称", "status", 1),
			operatorToken), "改自己的资料");
		assertThat(nicknameOf(operatorId)).isEqualTo("改自己的昵称");
	}

	// ---------- 辅助 ----------

	private String nextName(String tag) {
		return "it_dis_" + tag + "_" + TS + "_" + (++seq);
	}

	/** 用这个令牌查自己的登录信息：登录有效是 200，被踢下线是 401 */
	private int httpStatusOfAuthInfo(String token) {
		return get("/auth/info", token).getStatusCode().value();
	}

	/** 用建用户时的口令再登录一次，不要求成功，返回响应体 */
	private JsonNode tryLogin(String username) {
		JsonNode keyData = readBody(get("/auth/sm2-public-key", null)).path("data");
		String password = NEW_USER_PASSWORD;
		if (keyData.path("gmEnabled").asBoolean()) {
			password = sm2Encrypt(password, keyData.path("publicKey").asText());
		}
		Map<String, Object> body = new HashMap<>();
		body.put("tenantId", PLATFORM_TENANT);
		body.put("username", username);
		body.put("password", password);
		body.put("clientId", CLIENT_WEB);
		return readBody(post("/auth/login", body, null));
	}

	/** 平台管理员建用户，返回用户 id */
	private long createUser(String username, List<Long> roleIds) {
		Map<String, Object> user = new HashMap<>();
		user.put("username", username);
		user.put("nickname", "集成测试-" + username);
		user.put("password", NEW_USER_PASSWORD);
		user.put("status", 1);
		user.put("roleIds", roleIds);
		assertOk(post("/system/user/submit", user, adminToken), "建用户 " + username);
		return jdbc.queryForObject(
			"select id from sys_user where username = ? and tenant_id = ? and coalesce(is_deleted, 0) = 0",
			Long.class, username, PLATFORM_TENANT);
	}

	private long createRole(String code, List<Long> menuIds) {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "集成测试-" + code);
		role.put("roleCode", code);
		role.put("sort", 92);
		role.put("dataScope", DataScopeConstants.ALL);
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

	private int statusOf(long userId) {
		return jdbc.queryForObject("select status from sys_user where id = ?", Integer.class, userId);
	}

	private String nicknameOf(long userId) {
		return jdbc.queryForObject("select nickname from sys_user where id = ?", String.class, userId);
	}

	/** 导入表格的一行；昵称、状态为 null 表示这一列不填 */
	private SysUserExcel excelRow(String username, String nickname, Integer status) {
		SysUserExcel row = new SysUserExcel();
		row.setUsername(username);
		row.setNickname(nickname);
		row.setStatus(status);
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
