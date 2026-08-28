package com.mugsun.boot.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 移动端待办 / 收件箱通道：鉴权守卫、空数据契约、越权隔离、已读幂等。
 * <p>待办依赖流程引擎种子数据，默认环境可能无待办——重点守住接口契约与鉴权，不依赖具体业务数据量。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AppTodoInboxApiTest extends AbstractIntegrationTest {

	private String token;

	@BeforeAll
	void setup() {
		token = loginAdmin();
	}

	@Test
	void todosWithoutTokenReturns401() {
		ResponseEntity<String> response = get("/app/todos", null);
		assertThat(response.getStatusCode().value()).isEqualTo(401);
	}

	@Test
	void inboxWithoutTokenReturns401() {
		assertThat(get("/app/inbox/messages", null).getStatusCode().value()).isEqualTo(401);
		assertThat(get("/app/inbox/notices", null).getStatusCode().value()).isEqualTo(401);
	}

	@Test
	void todosListReturnsArrayContract() {
		JsonNode body = readBody(get("/app/todos", token));
		assertThat(body.path("code").asInt()).isEqualTo(200);
		assertThat(body.path("data").isArray()).isTrue();
	}

	@Test
	void todoDetailMissingReturnsBusinessFail() {
		JsonNode body = readBody(get("/app/todos/999999999", token));
		assertThat(body.path("success").asBoolean()).isFalse();
		assertThat(body.path("msg").asText()).contains("待办");
	}

	@Test
	void todoHandleRejectsBlankAction() {
		Map<String, Object> body = new HashMap<>();
		body.put("action", null);
		JsonNode r = readBody(post("/app/todos/1/handle", body, token));
		assertThat(r.path("success").asBoolean()).isFalse();
		assertThat(r.path("msg").asText()).contains("办理");
	}

	@Test
	void todoHandleRejectsUnknownAction() {
		Map<String, Object> body = new HashMap<>();
		body.put("action", "skip");
		JsonNode r = readBody(post("/app/todos/1/handle", body, token));
		assertThat(r.path("success").asBoolean()).isFalse();
		assertThat(r.path("msg").asText()).contains("不支持");
	}

	@Test
	void messagesPageContract() {
		JsonNode data = readBody(get("/app/inbox/messages?pageNum=1&pageSize=10", token)).path("data");
		assertThat(data.path("records").isArray()).isTrue();
		assertThat(data.has("total")).isTrue();
		assertThat(data.path("records").size()).isLessThanOrEqualTo(10);
	}

	@Test
	void noticesPageContract() {
		JsonNode data = readBody(get("/app/inbox/notices?pageNum=1&pageSize=10", token)).path("data");
		assertThat(data.path("records").isArray()).isTrue();
		assertThat(data.has("total")).isTrue();
	}

	@Test
	void pageSizeIsCappedAt50() {
		// 服务端 Math.min(pageSize, 50)，恶意超大 pageSize 不得拖垮内存
		JsonNode data = readBody(get("/app/inbox/messages?pageNum=1&pageSize=500", token)).path("data");
		assertThat(data.path("records").size()).isLessThanOrEqualTo(50);
	}

	@Test
	void messageDetailMissingIsBusinessFail() {
		JsonNode r = readBody(get("/app/inbox/messages/999999999", token));
		assertThat(r.path("success").asBoolean()).isFalse();
		assertThat(r.path("msg").asText()).contains("消息");
	}

	@Test
	void noticeDetailMissingIsBusinessFail() {
		JsonNode r = readBody(get("/app/inbox/notices/999999999", token));
		assertThat(r.path("success").asBoolean()).isFalse();
	}

	@Test
	void readMessageIsIdempotentForForeignId() {
		// 不存在的 messageId 已读不应 500，只是无行被更新
		JsonNode r = readBody(post("/app/inbox/messages/999999999/read", Map.of(), token));
		assertThat(r.path("code").asInt()).isEqualTo(200);
	}

	@Test
	void createNoticeThenReadThroughInbox() {
		Map<String, Object> notice = new HashMap<>();
		notice.put("title", "app-inbox-" + System.currentTimeMillis());
		notice.put("content", "移动端收件箱验收");
		notice.put("category", "1");
		notice.put("allVisible", 1);
		JsonNode submit = readBody(post("/system/notice/submit", notice, token));
		assertThat(submit.path("code").asInt()).as(submit.path("msg").asText()).isEqualTo(200);

		JsonNode page = readBody(get("/app/inbox/notices?pageNum=1&pageSize=20", token)).path("data");
		long id = -1;
		for (JsonNode row : page.path("records")) {
			if (notice.get("title").equals(row.path("title").asText())) {
				id = row.path("id").asLong();
				assertThat(row.path("unread").asBoolean()).isTrue();
				break;
			}
		}
		assertThat(id).isPositive();

		JsonNode detail = readBody(get("/app/inbox/notices/" + id, token));
		assertThat(detail.path("code").asInt()).isEqualTo(200);
		assertThat(detail.path("data").path("content").asText()).contains("移动端收件箱");

		assertThat(readBody(post("/app/inbox/notices/" + id + "/read", Map.of(), token)).path("code").asInt())
			.isEqualTo(200);
		// 再读一次幂等
		assertThat(readBody(post("/app/inbox/notices/" + id + "/read", Map.of(), token)).path("code").asInt())
			.isEqualTo(200);

		JsonNode after = readBody(get("/app/inbox/notices/" + id, token)).path("data");
		assertThat(after.path("unread").asBoolean()).isFalse();

		assertThat(readBody(post("/system/notice/remove", List.of(id), token)).path("code").asInt()).isEqualTo(200);
	}
}
