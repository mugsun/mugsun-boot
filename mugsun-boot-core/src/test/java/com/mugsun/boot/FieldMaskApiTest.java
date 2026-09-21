package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字段级权限（G74）回归：手机号/身份证按角色权限码三级裁决——有明文权→明文、仅查看权→脱敏、无查看权→null。
 * 前置：经 admin API 建目标用户（落真实手机号/身份证）+ 两档角色（仅查看码 / 无查看码，行级范围均为全部以聚焦字段级），
 * 再以各用户真实登录读 detail/page。
 * <p>决策绑权限码而非绑端点：detail 与 page 两条读管线表现必须一致（本类逐端点对照断言）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FieldMaskApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final String PWD = "123456";

	private static final String ROLE_MASKED = "it-fm-mask-" + TS;
	private static final String ROLE_NOVIEW = "it-fm-noview-" + TS;
	private static final String VIEW_MASKED = "it-fm-vm-" + TS;
	private static final String VIEW_NOVIEW = "it-fm-vn-" + TS;
	private static final String TARGET = "it-fm-target-" + TS;
	/** 目标手机号（138 + 时间戳后 8 位，符合 ^1\d{10}$ 且唯一）；脱敏形态 138****后4位 */
	private static final String TARGET_PHONE = "138" + TS.substring(TS.length() - 8);
	private static final String MASKED_PHONE = "138****" + TS.substring(TS.length() - 4);
	private static final String TARGET_ID_CARD = "11010119900101001X";

	private String adminToken;
	private long targetId;
	private String tokenMasked;
	private String tokenNoView;

	@BeforeAll
	void setupMaskFixture() {
		adminToken = loginAdmin();
		long userListMenuId = findMenuIdByPermission("sys:user:list");
		long phoneViewMenuId = findMenuIdByPermission("sys:user:phone");

		// 1) 两档字段权限角色：行级范围全部（聚焦字段级裁决，行级由 DataScopeApiTest 覆盖）
		long maskedRoleId = createRole(ROLE_MASKED);
		long noViewRoleId = createRole(ROLE_NOVIEW);
		grantRoleMenus(maskedRoleId, List.of(userListMenuId, phoneViewMenuId));
		grantRoleMenus(noViewRoleId, List.of(userListMenuId));

		// 2) 目标用户：admin 持明文权建卡，手机号明文落库、身份证 SM4 密文落库
		Map<String, Object> target = new HashMap<>();
		target.put("username", TARGET);
		target.put("nickname", "集成测试脱敏目标");
		target.put("password", PWD);
		target.put("status", 1);
		target.put("phone", TARGET_PHONE);
		target.put("idCard", TARGET_ID_CARD);
		JsonNode r = readBody(post("/system/user/submit", target, adminToken));
		assertThat(r.path("code").asInt()).as("建目标用户：" + r.path("msg").asText()).isEqualTo(200);
		targetId = findUserIdByUsername(TARGET);

		// 3) 观察员用户并授角色（不带手机号，仅作读方）
		createUserWithRoles(VIEW_MASKED, maskedRoleId);
		createUserWithRoles(VIEW_NOVIEW, noViewRoleId);

		tokenMasked = login(PLATFORM_TENANT, VIEW_MASKED, PWD);
		tokenNoView = login(PLATFORM_TENANT, VIEW_NOVIEW, PWD);
	}

	@Test
	void adminSeesPlainPhoneAndIdCard() {
		// 超管通配 * 命中 sys:user:phone:plain / sys:user:idcard:plain → 全明文
		JsonNode r = readBody(get("/system/user/detail?id=" + targetId, adminToken));
		assertThat(r.path("code").asInt()).isEqualTo(200);
		JsonNode data = r.path("data");
		assertThat(data.path("username").asText()).isEqualTo(TARGET);
		assertThat(data.path("phone").asText()).isEqualTo(TARGET_PHONE);
		assertThat(data.path("idCard").asText()).isEqualTo(TARGET_ID_CARD);
	}

	@Test
	void viewOnlyRoleSeesMaskedPhoneAndNullIdCard() {
		// 有 sys:user:phone（查看权）无明文权 → 脱敏 138****XXXX；无 sys:user:idcard → 身份证列不可见（null）
		JsonNode r = readBody(get("/system/user/detail?id=" + targetId, tokenMasked));
		assertThat(r.path("code").asInt()).isEqualTo(200);
		JsonNode data = r.path("data");
		assertThat(data.path("username").asText()).isEqualTo(TARGET);
		assertThat(data.path("phone").asText()).isEqualTo(MASKED_PHONE);
		assertThat(data.path("idCard").isNull()).as("无身份证查看权则该列为 null").isTrue();
	}

	@Test
	void noViewRoleSeesNullPhoneAndIdCard() {
		// 无任何字段查看权 → 敏感列一律 null；行本身与非敏感字段仍可见（字段级不影响行级）
		JsonNode r = readBody(get("/system/user/detail?id=" + targetId, tokenNoView));
		assertThat(r.path("code").asInt()).isEqualTo(200);
		JsonNode data = r.path("data");
		assertThat(data.path("username").asText()).isEqualTo(TARGET);
		assertThat(data.path("nickname").asText()).isEqualTo("集成测试脱敏目标");
		assertThat(data.path("phone").isNull()).as("无手机号查看权则该列为 null").isTrue();
		assertThat(data.path("idCard").isNull()).as("无身份证查看权则该列为 null").isTrue();
	}

	@Test
	void pagePipelineAppliesSameMasking() {
		// 读管线一致性：page 列表与 detail 同口径（决策绑字段权限码，不绑端点）
		JsonNode masked = readBody(get("/system/user/page?pageNum=1&pageSize=20&username=" + TARGET, tokenMasked));
		assertThat(masked.path("code").asInt()).isEqualTo(200);
		JsonNode maskedRecord = onlyRecord(masked);
		assertThat(maskedRecord.path("phone").asText()).isEqualTo(MASKED_PHONE);
		assertThat(maskedRecord.path("idCard").isNull()).isTrue();

		JsonNode noView = readBody(get("/system/user/page?pageNum=1&pageSize=20&username=" + TARGET, tokenNoView));
		JsonNode noViewRecord = onlyRecord(noView);
		assertThat(noViewRecord.path("phone").isNull()).isTrue();
		assertThat(noViewRecord.path("idCard").isNull()).isTrue();
	}

	private JsonNode onlyRecord(JsonNode pageBody) {
		JsonNode records = pageBody.path("data").path("records");
		assertThat(records.size()).as("按用户名精确过滤应恰一行").isEqualTo(1);
		return records.get(0);
	}

	// ---------- 前置构造辅助（均走 admin 真实 API） ----------

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

	private long createRole(String roleCode) {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "集成测试字段权限-" + roleCode);
		role.put("roleCode", roleCode);
		role.put("sort", 90);
		// 行级数据范围全部：本类聚焦字段级三级裁决，行级过滤由 DataScopeApiTest 覆盖
		role.put("dataScope", 1);
		JsonNode r = readBody(post("/system/role/submit", role, adminToken));
		assertThat(r.path("code").asInt()).as("建角色：" + r.path("msg").asText()).isEqualTo(200);
		JsonNode page = readBody(get("/system/role/page?pageNum=1&pageSize=200&roleCode=" + roleCode, adminToken));
		for (JsonNode record : page.path("data").path("records")) {
			if (roleCode.equals(record.path("roleCode").asText())) {
				return record.path("id").asLong();
			}
		}
		throw new IllegalStateException("角色分页中未找到：" + roleCode);
	}

	private void grantRoleMenus(long roleId, List<Long> menuIds) {
		Map<String, Object> grant = new HashMap<>();
		grant.put("roleId", roleId);
		grant.put("menuIds", menuIds);
		JsonNode r = readBody(post("/system/role/grant", grant, adminToken));
		assertThat(r.path("code").asInt()).as("角色授菜单：" + r.path("msg").asText()).isEqualTo(200);
	}

	private void createUserWithRoles(String username, long roleId) {
		Map<String, Object> user = new HashMap<>();
		user.put("username", username);
		user.put("nickname", "集成测试-" + username);
		user.put("password", PWD);
		user.put("status", 1);
		JsonNode r = readBody(post("/system/user/submit", user, adminToken));
		assertThat(r.path("code").asInt()).as("建用户：" + r.path("msg").asText()).isEqualTo(200);
		long userId = findUserIdByUsername(username);
		Map<String, Object> grant = new HashMap<>();
		grant.put("userId", userId);
		grant.put("roleIds", List.of(roleId));
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
}
