package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.tenant.TenantPackageModules;
import org.junit.jupiter.api.AfterAll;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 反馈附件 {@code GET /system/feedback/attachment/{id}}：提交人传的图片随反馈提交，管理员按反馈读到原图。
 * 只有平台管理员能读；其他租户的用户提交的也读得到；提交时引用不了别人传的附件。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeedbackAttachmentApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final String ATTACHMENT = "/system/feedback/attachment/";
	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

	private final List<Long> feedbackIds = new ArrayList<>();
	private String adminToken;
	/** 只有上传权限的普通用户 */
	private String userToken;
	private String otherTenantAdminToken;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		userToken = userWithMenus("it-fba-role-" + TS, "it_fba_u_" + TS,
			List.of(findMenuIdByPermission("sys:file:upload")));

		Map<String, Object> tenant = new HashMap<>();
		tenant.put("tenantName", "集成测试-反馈附件-" + TS);
		tenant.put("contactUser", "集成测试");
		JsonNode created = readBody(post("/system/tenant/create", tenant, adminToken));
		assertThat(created.path("code").asInt()).as("建租户：" + created.path("msg").asText()).isEqualTo(200);
		String otherTenant = tenantCodeOf(created);
		normalizeTenantAdminPassword(otherTenant);
		otherTenantAdminToken = login(otherTenant, ADMIN_USERNAME, ADMIN_PASSWORD);
	}

	@AfterAll
	void removeFeedback() {
		if (!feedbackIds.isEmpty()) {
			post("/system/feedback/remove", feedbackIds, adminToken);
		}
	}

	@Test
	void managerReadsTheImageAnOrdinaryUserAttached() {
		JsonNode attach = upload(userToken, "shot.png");
		assertThat(attach.path("access").asText()).isEqualTo("private");
		String content = "it-fba-own-" + TS;
		JsonNode submit = submit(userToken, content, attach.path("id").asLong());
		assertThat(submit.path("code").asInt()).as("提交：" + submit.path("msg").asText()).isEqualTo(200);

		JsonNode row = feedbackRow(content);
		assertThat(row).as("管理员的列表里应有这条反馈").isNotNull();
		assertThat(row.path("attachName").asText()).isEqualTo("shot.png");
		assertThat(row.path("attachUrl").asText("")).as("不再存附件地址").isEmpty();
		long feedbackId = row.path("id").asLong();

		ResponseEntity<byte[]> image = getBytes(ATTACHMENT + feedbackId, adminToken);
		assertThat(image.getStatusCode().value()).isEqualTo(200);
		assertThat(image.getBody()).isEqualTo(PNG);
		assertThat(image.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).startsWith("image/png");
		assertThat(image.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
		assertThat(image.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
			.startsWith("attachment").contains("shot.png");

		// 提交人自己没有反馈管理权限，读不到
		assertThat(getBytes(ATTACHMENT + feedbackId, userToken).getStatusCode().value()).isEqualTo(403);
	}

	@Test
	void submitRefusesAnAttachmentUploadedBySomeoneElse() {
		JsonNode adminAttach = upload(adminToken, "admin.png");
		String content = "it-fba-steal-" + TS;
		JsonNode submit = submit(userToken, content, adminAttach.path("id").asLong());
		assertThat(submit.path("code").asInt()).isNotEqualTo(200);
		assertThat(submit.path("msg").asText()).contains("附件不存在");
		assertThat(feedbackRow(content)).as("被拒绝的反馈不应入库").isNull();
		// 上传人自己提交照常可以
		String own = "it-fba-admin-own-" + TS;
		assertThat(submit(adminToken, own, adminAttach.path("id").asLong()).path("code").asInt()).isEqualTo(200);
		assertThat(feedbackRow(own)).isNotNull();
	}

	@Test
	void feedbackWithoutAttachmentOrUnknownFeedbackIs404() {
		String content = "it-fba-plain-" + TS;
		assertThat(submit(userToken, content, null).path("code").asInt()).isEqualTo(200);
		long feedbackId = feedbackRow(content).path("id").asLong();
		assertThat(getBytes(ATTACHMENT + feedbackId, adminToken).getStatusCode().value()).isEqualTo(404);
		assertThat(getBytes(ATTACHMENT + "999999999999999999", adminToken).getStatusCode().value()).isEqualTo(404);
	}

	@Test
	void platformManagerReadsAttachmentFromAnotherTenantButThatTenantsAdminCannot() {
		JsonNode attach = upload(otherTenantAdminToken, "tenant.png");
		String content = "it-fba-tenant-" + TS;
		assertThat(submit(otherTenantAdminToken, content, attach.path("id").asLong()).path("code").asInt()).isEqualTo(200);
		long feedbackId = feedbackRow(content).path("id").asLong();

		ResponseEntity<byte[]> image = getBytes(ATTACHMENT + feedbackId, adminToken);
		assertThat(image.getStatusCode().value()).isEqualTo(200);
		assertThat(image.getBody()).isEqualTo(PNG);
		// 通用下载按租户隔离，平台管理员从那边读不到别的租户的附件，所以要按反馈读
		assertThat(getBytes("/system/file/download-stream/" + attach.path("id").asText(), adminToken)
			.getStatusCode().value()).isEqualTo(404);
		// 其他租户的管理员调不了反馈管理面，按反馈读附件也一样
		assertThat(getBytes(ATTACHMENT + feedbackId, otherTenantAdminToken).getStatusCode().value()).isEqualTo(403);
		assertThat(TenantPackageModules.isPlatformOnly(ATTACHMENT + feedbackId)).isTrue();
	}

	private JsonNode submit(String token, String content, Long attachId) {
		Map<String, Object> body = new HashMap<>();
		body.put("content", content);
		if (attachId != null) {
			body.put("attachId", attachId);
		}
		return readBody(post("/system/feedback/submit", body, token));
	}

	/** 管理员列表里内容完全相同的那条反馈；没有返回 null。找到的记下来，测试结束时删掉 */
	private JsonNode feedbackRow(String content) {
		JsonNode page = readBody(get("/system/feedback/page?pageNum=1&pageSize=50&name=" + content, adminToken));
		assertThat(page.path("code").asInt()).isEqualTo(200);
		for (JsonNode row : page.path("data").path("records")) {
			if (content.equals(row.path("content").asText())) {
				feedbackIds.add(row.path("id").asLong());
				return row;
			}
		}
		return null;
	}

	private JsonNode upload(String token, String filename) {
		HttpHeaders headers = authHeaders(token);
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", new ByteArrayResource(PNG) {
			@Override
			public String getFilename() {
				return filename;
			}
		});
		JsonNode json = readBody(rest.exchange("/system/file/upload", HttpMethod.POST,
			new HttpEntity<>(body, headers), String.class));
		assertThat(json.path("code").asInt()).as("上传：" + json.path("msg").asText()).isEqualTo(200);
		return json.path("data");
	}

	private ResponseEntity<byte[]> getBytes(String url, String token) {
		return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(authHeaders(token)), byte[].class);
	}

	private String userWithMenus(String roleCode, String username, List<Long> menuIds) {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "集成测试反馈附件-" + roleCode);
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
		user.put("password", NEW_USER_PASSWORD);
		user.put("status", 1);
		JsonNode ur = readBody(post("/system/user/submit", user, adminToken));
		assertThat(ur.path("code").asInt()).as("建用户：" + ur.path("msg").asText()).isEqualTo(200);
		long userId = findId("/system/user/page?pageNum=1&pageSize=200&username=" + username, "username", username);

		Map<String, Object> grant = new HashMap<>();
		grant.put("userId", userId);
		grant.put("roleIds", List.of(roleId));
		JsonNode ug = readBody(post("/system/user/grant", grant, adminToken));
		assertThat(ug.path("code").asInt()).as("用户授权：" + ug.path("msg").asText()).isEqualTo(200);
		return loginNewUser(PLATFORM_TENANT, username, NEW_USER_PASSWORD);
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
		if (nodes == null || !nodes.isArray()) {
			return null;
		}
		for (JsonNode node : nodes) {
			if (permission.equals(node.path("permission").asText())) {
				return node.path("id").asLong();
			}
			Long child = findMenuIdInNodes(node.path("children"), permission);
			if (child != null) {
				return child;
			}
		}
		return null;
	}
}
