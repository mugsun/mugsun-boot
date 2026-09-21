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
 * 内容域回归：帮助文档目录/文档/浏览量、更新日志、意见反馈提交与管理；低权写 403。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final String LOW_USERNAME = "it-cnt-none-" + TS;
	private static final String LOW_PASSWORD = "123456";

	private String adminToken;
	private String lowToken;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();
		zeroPermissionLogin(adminToken);
	}

	@Test
	void helpCatalogDocBindAndViewCount() {
		Map<String, Object> catalog = new HashMap<>();
		catalog.put("parentId", 0);
		catalog.put("name", "IT帮助目录-" + TS);
		catalog.put("sort", 1);
		JsonNode catSubmit = readBody(post("/system/help/catalog/submit", catalog, adminToken));
		assertThat(catSubmit.path("code").asInt()).as("目录：" + catSubmit.path("msg").asText()).isEqualTo(200);

		JsonNode tree = readBody(get("/system/help/catalog/tree", adminToken));
		assertThat(tree.path("code").asInt()).isEqualTo(200);
		long catalogId = findCatalogId(tree.path("data"), "IT帮助目录-" + TS);
		assertThat(catalogId).isPositive();

		Map<String, Object> doc = new HashMap<>();
		doc.put("catalogId", catalogId);
		doc.put("title", "IT帮助文档-" + TS);
		doc.put("content", "<p>集成测试正文</p>");
		doc.put("sort", 1);
		JsonNode docSubmit = readBody(post("/system/help/doc/submit", doc, adminToken));
		assertThat(docSubmit.path("code").asInt()).as("文档：" + docSubmit.path("msg").asText()).isEqualTo(200);

		JsonNode page = readBody(get("/system/help/doc/page?pageNum=1&pageSize=50&title=IT帮助文档-" + TS, adminToken));
		assertThat(page.path("code").asInt()).isEqualTo(200);
		long docId = -1;
		for (JsonNode row : page.path("data").path("records")) {
			if (("IT帮助文档-" + TS).equals(row.path("title").asText())) {
				docId = row.path("id").asLong();
				break;
			}
		}
		assertThat(docId).isPositive();

		Map<String, Object> binding = new HashMap<>();
		binding.put("docId", docId);
		binding.put("routePath", "/system/param");
		JsonNode bind = readBody(post("/system/help/binding/submit", binding, adminToken));
		assertThat(bind.path("code").asInt()).as("绑定：" + bind.path("msg").asText()).isEqualTo(200);

		JsonNode before = readBody(get("/system/help/doc/detail?id=" + docId, adminToken));
		long viewBefore = before.path("data").path("viewCount").asLong(0);

		JsonNode view = readBody(post("/system/help/view/" + docId, Map.of(), adminToken));
		assertThat(view.path("code").asInt()).isEqualTo(200);

		JsonNode after = readBody(get("/system/help/doc/detail?id=" + docId, adminToken));
		assertThat(after.path("data").path("viewCount").asLong())
			.as("浏览量应 +1").isEqualTo(viewBefore + 1);

		assertThat(readBody(post("/system/help/doc/remove", List.of(docId), adminToken)).path("code").asInt()).isEqualTo(200);
		assertThat(readBody(post("/system/help/catalog/remove/" + catalogId, Map.of(), adminToken)).path("code").asInt()).isEqualTo(200);
	}

	@Test
	void changelogSubmitRecentAndRemove() {
		Map<String, Object> log = new HashMap<>();
		log.put("version", "it-" + TS);
		log.put("type", "feature");
		log.put("title", "IT更新日志-" + TS);
		log.put("content", "<p>集成测试</p>");
		log.put("sort", 1);
		JsonNode submit = readBody(post("/system/changelog/submit", log, adminToken));
		assertThat(submit.path("code").asInt()).as("changelog：" + submit.path("msg").asText()).isEqualTo(200);

		JsonNode recent = readBody(get("/system/changelog/recent?limit=20", adminToken));
		assertThat(recent.path("code").asInt()).isEqualTo(200);
		long id = -1;
		for (JsonNode row : recent.path("data")) {
			if (("IT更新日志-" + TS).equals(row.path("title").asText())) {
				id = row.path("id").asLong();
				break;
			}
		}
		assertThat(id).isPositive();

		JsonNode remove = readBody(post("/system/changelog/remove", List.of(id), adminToken));
		assertThat(remove.path("code").asInt()).isEqualTo(200);
	}

	@Test
	void feedbackSubmitAndManage() {
		Map<String, Object> fb = new HashMap<>();
		fb.put("content", "IT反馈内容-" + TS);
		fb.put("contact", "it@test.local");
		JsonNode submit = readBody(post("/system/feedback/submit", fb, adminToken));
		assertThat(submit.path("code").asInt()).as("反馈：" + submit.path("msg").asText()).isEqualTo(200);

		JsonNode page = readBody(get("/system/feedback/page?pageNum=1&pageSize=50", adminToken));
		assertThat(page.path("code").asInt()).isEqualTo(200);
		long id = -1;
		for (JsonNode row : page.path("data").path("records")) {
			if (("IT反馈内容-" + TS).equals(row.path("content").asText())) {
				id = row.path("id").asLong();
				assertThat(row.path("status").asInt()).isEqualTo(0);
				break;
			}
		}
		assertThat(id).isPositive();

		JsonNode status = readBody(post("/system/feedback/status/" + id, Map.of(), adminToken));
		assertThat(status.path("code").asInt()).isEqualTo(200);
		JsonNode after = readBody(get("/system/feedback/page?pageNum=1&pageSize=50&status=1", adminToken));
		boolean processed = false;
		for (JsonNode row : after.path("data").path("records")) {
			if (row.path("id").asLong() == id) {
				processed = row.path("status").asInt() == 1;
			}
		}
		assertThat(processed).isTrue();

		assertThat(readBody(post("/system/feedback/remove", List.of(id), adminToken)).path("code").asInt()).isEqualTo(200);
	}

	@Test
	void lowPermissionCannotManageHelpOrChangelogOrFeedbackPage() {
		Map<String, Object> catalog = Map.of("parentId", 0, "name", "越权目录", "sort", 99);
		ResponseEntity<String> help = post("/system/help/catalog/submit", catalog, lowToken);
		assertThat(help.getStatusCode().value()).isEqualTo(403);

		ResponseEntity<String> changelog = post("/system/changelog/submit",
			Map.of("version", "x", "type", "fix", "title", "越权", "content", "x"), lowToken);
		assertThat(changelog.getStatusCode().value()).isEqualTo(403);

		ResponseEntity<String> feedbackPage = get("/system/feedback/page?pageNum=1&pageSize=10", lowToken);
		assertThat(feedbackPage.getStatusCode().value()).isEqualTo(403);

		// 任意登录用户仍可提交反馈
		JsonNode ok = readBody(post("/system/feedback/submit",
			Map.of("content", "低权用户合法反馈-" + TS), lowToken));
		assertThat(ok.path("code").asInt()).isEqualTo(200);
	}

	private long findCatalogId(JsonNode nodes, String name) {
		if (nodes == null || !nodes.isArray()) {
			return -1;
		}
		for (JsonNode node : nodes) {
			if (name.equals(node.path("name").asText())) {
				return node.path("id").asLong();
			}
			long child = findCatalogId(node.path("children"), name);
			if (child > 0) {
				return child;
			}
		}
		return -1;
	}

	private void zeroPermissionLogin(String platformAdmin) {
		String roleCode = "it-cnt-role-" + TS;
		Map<String, Object> role = new HashMap<>();
		role.put("roleName", "集成测试内容零权限角色");
		role.put("roleCode", roleCode);
		role.put("sort", 99);
		role.put("dataScope", 1);
		assertThat(readBody(post("/system/role/submit", role, platformAdmin)).path("code").asInt()).isEqualTo(200);

		Map<String, Object> user = new HashMap<>();
		user.put("username", LOW_USERNAME);
		user.put("nickname", "集成测试内容低权用户");
		user.put("password", LOW_PASSWORD);
		user.put("status", 1);
		assertThat(readBody(post("/system/user/submit", user, platformAdmin)).path("code").asInt()).isEqualTo(200);

		JsonNode rolePage = readBody(get("/system/role/page?pageNum=1&pageSize=200&roleCode=" + roleCode, platformAdmin));
		long roleId = -1;
		for (JsonNode record : rolePage.path("data").path("records")) {
			if (roleCode.equals(record.path("roleCode").asText())) {
				roleId = record.path("id").asLong();
			}
		}
		JsonNode userPage = readBody(get("/system/user/page?pageNum=1&pageSize=200&username=" + LOW_USERNAME, platformAdmin));
		long userId = -1;
		for (JsonNode record : userPage.path("data").path("records")) {
			if (LOW_USERNAME.equals(record.path("username").asText())) {
				userId = record.path("id").asLong();
			}
		}
		assertThat(roleId).isPositive();
		assertThat(userId).isPositive();
		Map<String, Object> grant = new HashMap<>();
		grant.put("userId", userId);
		grant.put("roleIds", List.of(roleId));
		assertThat(readBody(post("/system/user/grant", grant, platformAdmin)).path("code").asInt()).isEqualTo(200);
		lowToken = login(PLATFORM_TENANT, LOW_USERNAME, LOW_PASSWORD);
	}
}
