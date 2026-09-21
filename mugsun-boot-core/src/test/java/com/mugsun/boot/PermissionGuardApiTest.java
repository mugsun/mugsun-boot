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
 * 权限兜底守卫（fail-closed）回归：无权限码角色用户对受控接口一律 403。
 * 前置：经 admin API 建「零菜单角色」+ 用户并授权，再以该用户真实登录。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PermissionGuardApiTest extends AbstractIntegrationTest {

	private static final String ROLE_CODE = "it-no-perm";
	private static final String LOW_USERNAME = "it-lowpriv";
	private static final String LOW_PASSWORD = "123456";

	private String lowToken;

	@BeforeAll
	void setupLowPrivilegeUser() {
		String adminToken = loginAdmin();

		// 1) 建零权限角色（不授任何菜单）
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "集成测试零权限角色");
		role.put("roleCode", ROLE_CODE);
		role.put("sort", 99);
		role.put("dataScope", 1);
		JsonNode roleResp = readBody(post("/system/role/submit", role, adminToken));
		assertThat(roleResp.path("code").asInt()).as("建角色：" + roleResp.path("msg").asText()).isEqualTo(200);

		// 2) 建用户（明文密码由服务端 BCrypt 落库）
		Map<String, Object> user = new HashMap<>();
		user.put("username", LOW_USERNAME);
		user.put("nickname", "集成测试低权用户");
		user.put("password", LOW_PASSWORD);
		user.put("status", 1);
		JsonNode userResp = readBody(post("/system/user/submit", user, adminToken));
		assertThat(userResp.path("code").asInt()).as("建用户：" + userResp.path("msg").asText()).isEqualTo(200);

		// 3) 找回角色/用户主键（大整数序列化为字符串，按文本比较）
		long roleId = findIdByField("/system/role/page?pageNum=1&pageSize=100", "roleCode", ROLE_CODE, adminToken);
		long userId = findIdByField("/system/user/page?pageNum=1&pageSize=100", "username", LOW_USERNAME, adminToken);

		// 4) 授权零权限角色
		Map<String, Object> grant = new HashMap<>();
		grant.put("userId", userId);
		grant.put("roleIds", List.of(roleId));
		JsonNode grantResp = readBody(post("/system/user/grant", grant, adminToken));
		assertThat(grantResp.path("code").asInt()).as("授权：" + grantResp.path("msg").asText()).isEqualTo(200);

		// 5) 低权用户真实登录（同前端链路）
		lowToken = login(PLATFORM_TENANT, LOW_USERNAME, LOW_PASSWORD);
	}

	private long findIdByField(String pageUrl, String field, String value, String token) {
		JsonNode page = readBody(get(pageUrl, token));
		assertThat(page.path("code").asInt()).isEqualTo(200);
		for (JsonNode record : page.path("data").path("records")) {
			if (value.equals(record.path(field).asText())) {
				return record.path("id").asLong();
			}
		}
		throw new IllegalStateException("分页中未找到 " + field + "=" + value);
	}

	@Test
	void lowPrivilegeUserHasNoAdminRole() {
		JsonNode r = readBody(get("/auth/info", lowToken));
		assertThat(r.path("code").asInt()).isEqualTo(200);
		java.util.List<String> roles = new java.util.ArrayList<>();
		r.path("data").path("roles").forEach(n -> roles.add(n.asText()));
		assertThat(roles).containsExactly(ROLE_CODE);
		java.util.List<String> buttons = new java.util.ArrayList<>();
		r.path("data").path("buttons").forEach(n -> buttons.add(n.asText()));
		assertThat(buttons).doesNotContain("*");
	}

	@Test
	void writeApiIsForbiddenForLowPrivilegeUser() {
		// /system/user/submit 内联自守卫 sys:user:add —— 无码即拒
		Map<String, Object> body = new HashMap<>();
		body.put("username", "it-hack");
		body.put("nickname", "越权尝试");
		body.put("status", 1);
		ResponseEntity<String> response = post("/system/user/submit", body, lowToken);
		assertThat(response.getStatusCode().value()).isEqualTo(403);
		JsonNode r = readBody(response);
		assertThat(r.path("code").asInt()).isEqualTo(403);
		assertThat(r.path("success").asBoolean()).isFalse();
	}

	@Test
	void readApiIsForbiddenForLowPrivilegeUser() {
		// @SaCheckPermission("sys:user:list") 原生注解校验 —— 无码即拒
		ResponseEntity<String> response = get("/system/user/page", lowToken);
		assertThat(response.getStatusCode().value()).isEqualTo(403);
		JsonNode r = readBody(response);
		assertThat(r.path("code").asInt()).isEqualTo(403);
	}

	@Test
	void jobReadApiIsForbiddenForLowPrivilegeUser() {
		// 定时任务读接口同样持码校验（历史曾仅 @SaCheckLogin 裸奔）—— 无码即拒
		ResponseEntity<String> response = get("/system/job/list", lowToken);
		assertThat(response.getStatusCode().value()).isEqualTo(403);
		JsonNode r = readBody(response);
		assertThat(r.path("code").asInt()).isEqualTo(403);
	}

	@Test
	void jobSaveWithInvalidProcessorIsRejected() {
		// 处理器必须在注册表（Spring 容器 BasicProcessor 实现）内：指向不存在类的保存直接 400，不落成死任务
		String adminToken = loginAdmin();
		Map<String, Object> body = new HashMap<>();
		body.put("jobName", "集成测试非法处理器任务");
		body.put("processorInfo", "com.mugsun.boot.job.NotExistProcessor");
		ResponseEntity<String> response = post("/system/job/save", body, adminToken);
		JsonNode r = readBody(response);
		assertThat(r.path("code").asInt()).isEqualTo(400);
		assertThat(r.path("msg").asText()).contains("处理器未注册");
	}
}
