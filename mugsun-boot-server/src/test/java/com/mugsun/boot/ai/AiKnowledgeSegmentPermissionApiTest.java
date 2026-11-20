package com.mugsun.boot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BOOT-090：改分段内容、启停分段要「编辑分段」权限（ai:kb-seg:edit）。
 * 只有知识库详情权限（ai:kb:detail）的账号能看分段，不能改。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiKnowledgeSegmentPermissionApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final String KB = "/system/ai/knowledge";

	private String adminToken;
	private String viewerToken;
	private String editorToken;
	private long knowledgeId;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		long list = findMenuIdByPermission("ai:kb:list");
		long detail = findMenuIdByPermission("ai:kb:detail");
		long segEdit = findMenuIdByPermission("ai:kb-seg:edit");
		viewerToken = userWithMenus("it-kbseg-v-" + TS, "it_kbseg_v_" + TS, List.of(list, detail));
		editorToken = userWithMenus("it-kbseg-e-" + TS, "it_kbseg_e_" + TS, List.of(list, detail, segEdit));

		Map<String, Object> kb = new HashMap<>();
		kb.put("name", "it-kbseg-" + TS);
		kb.put("description", "集成测试：分段权限");
		JsonNode created = readBody(post(KB + "/submit", kb, adminToken));
		assertThat(created.path("code").asInt()).as("建知识库：" + created.path("msg").asText()).isEqualTo(200);
		knowledgeId = created.path("data").path("id").asLong();

		Map<String, Object> asset = new HashMap<>();
		asset.put("knowledgeId", knowledgeId);
		asset.put("fileName", "报销制度.txt");
		asset.put("fileType", "text");
		asset.put("contentText", "第一条：报销单须在费用发生后 30 天内提交。\n\n第二条：单笔超过 5000 元须部门负责人审批。");
		asset.put("segmentType", "paragraph");
		JsonNode uploaded = readBody(post(KB + "/asset/upload", asset, adminToken));
		assertThat(uploaded.path("code").asInt()).as("传资料：" + uploaded.path("msg").asText()).isEqualTo(200);
		assertThat(segments(adminToken)).as("按段落切出两段").hasSize(2);
	}

	@AfterAll
	void removeKnowledge() {
		if (knowledgeId != 0) {
			post(KB + "/remove?ids=" + knowledgeId, null, adminToken);
		}
	}

	@Test
	@DisplayName("只有知识库详情权限：能看分段，改内容和启停都是 403，数据不变")
	void viewerReadsSegmentsButCannotChangeThem() {
		JsonNode seg = segments(viewerToken).get(0);
		long id = seg.path("id").asLong();
		String original = seg.path("content").asText();
		assertThat(original).startsWith("第一条");

		Map<String, Object> edit = new HashMap<>();
		edit.put("id", id);
		edit.put("content", "被改掉的内容");
		ResponseEntity<String> edited = post(KB + "/segment/submit", edit, viewerToken);
		assertThat(edited.getStatusCode().value()).as("改内容：" + edited.getBody()).isEqualTo(403);

		Map<String, Object> off = new HashMap<>();
		off.put("id", id);
		off.put("enabled", 0);
		ResponseEntity<String> disabled = post(KB + "/segment/status", off, viewerToken);
		assertThat(disabled.getStatusCode().value()).as("停用：" + disabled.getBody()).isEqualTo(403);

		JsonNode now = segments(adminToken).get(0);
		assertThat(now.path("content").asText()).isEqualTo(original);
		assertThat(now.path("enabled").asInt()).isEqualTo(1);
	}

	@Test
	@DisplayName("勾了「编辑分段」：改内容和启停都成功")
	void editorChangesSegments() {
		JsonNode seg = segments(editorToken).get(1);
		long id = seg.path("id").asLong();
		String changed = seg.path("content").asText() + "（已修订）";

		Map<String, Object> edit = new HashMap<>();
		edit.put("id", id);
		edit.put("content", changed);
		JsonNode edited = readBody(post(KB + "/segment/submit", edit, editorToken));
		assertThat(edited.path("code").asInt()).as("改内容：" + edited.path("msg").asText()).isEqualTo(200);

		Map<String, Object> off = new HashMap<>();
		off.put("id", id);
		off.put("enabled", 0);
		JsonNode disabled = readBody(post(KB + "/segment/status", off, editorToken));
		assertThat(disabled.path("code").asInt()).as("停用：" + disabled.path("msg").asText()).isEqualTo(200);

		JsonNode now = segments(adminToken).get(1);
		assertThat(now.path("content").asText()).isEqualTo(changed);
		assertThat(now.path("enabled").asInt()).isEqualTo(0);
	}

	private JsonNode segments(String token) {
		JsonNode resp = readBody(get(KB + "/segment/list?knowledgeId=" + knowledgeId, token));
		assertThat(resp.path("code").asInt()).as("看分段：" + resp.path("msg").asText()).isEqualTo(200);
		return resp.path("data");
	}

	private String userWithMenus(String roleCode, String username, List<Long> menuIds) {
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "集成测试分段权限-" + roleCode);
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
