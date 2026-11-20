package com.mugsun.boot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BOOT-091：关键词检索要看完所有分段，不能只看最早的 500 段；停用的知识库不再被检索。
 * 知识库用关键词模式，不依赖向量扩展和向量模型。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiKnowledgeRetrievalApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final int LINES = 600;
	private static final int TARGET_LINE = 590;

	private String adminToken;
	private long kbId;

	@BeforeAll
	void setup() {
		adminToken = loginAdmin();

		Map<String, Object> kb = new HashMap<>();
		kb.put("name", "it-kb-" + TS);
		kb.put("description", "集成测试：关键词检索和停用");
		kb.put("retrievalMode", "keyword");
		JsonNode created = readBody(post("/system/ai/knowledge/submit", kb, adminToken));
		assertThat(created.path("code").asInt()).as("建知识库：" + created.path("msg").asText()).isEqualTo(200);
		kbId = created.path("data").path("id").asLong();
		assertThat(kbId).isPositive();

		// 一行一段，要找的内容放在第 590 段：按 id 排在前 500 段之后
		StringBuilder text = new StringBuilder();
		for (int i = 1; i <= LINES; i++) {
			text.append("第 ").append(i).append(" 条：")
				.append(i == TARGET_LINE ? "报销单编号 ZX-7788 要部门负责人签字" : "日常办公说明").append('\n');
		}
		Map<String, Object> asset = new HashMap<>();
		asset.put("knowledgeId", kbId);
		asset.put("fileName", "it-kb-" + TS + ".txt");
		asset.put("fileType", "text");
		asset.put("contentText", text.toString());
		asset.put("segmentType", "line");
		asset.put("segmentLength", 500);
		asset.put("segmentOverlap", 0);
		JsonNode uploaded = readBody(post("/system/ai/knowledge/asset/upload", asset, adminToken));
		assertThat(uploaded.path("code").asInt()).as("上传资料：" + uploaded.path("msg").asText()).isEqualTo(200);
		assertThat(uploaded.path("data").path("segmentCount").asInt()).as("一行一段").isEqualTo(LINES);
	}

	@AfterAll
	void cleanup() {
		if (kbId > 0) {
			post("/system/ai/knowledge/remove?ids=" + kbId, null, adminToken);
		}
	}

	@Test
	@DisplayName("关键词检索能搜到第 500 段以后的内容")
	void keywordSearchReachesSegmentsBeyondTheFirst500() {
		JsonNode hit = hitTest("ZX-7788");

		assertThat(hit.path("code").asInt()).as("命中测试：" + hit.path("msg").asText()).isEqualTo(200);
		assertThat(hit.path("data")).as("命中的分段：" + hit.path("data")).hasSize(1);
		assertThat(hit.path("data").get(0).path("content").asText())
			.contains("第 " + TARGET_LINE + " 条").contains("ZX-7788");
	}

	@Test
	@DisplayName("停用的知识库：命中测试和列表里的快捷测试都明确报已停用，启用后恢复")
	void disabledKnowledgeBaseIsNotSearched() {
		JsonNode off = readBody(post("/system/ai/knowledge/status", Map.of("id", kbId, "status", 0), adminToken));
		assertThat(off.path("code").asInt()).as("停用：" + off.path("msg").asText()).isEqualTo(200);
		try {
			JsonNode hit = hitTest("ZX-7788");
			assertThat(hit.path("code").asInt()).as("停用后命中测试：" + hit).isNotEqualTo(200);
			assertThat(hit.path("msg").asText()).contains("已停用");

			JsonNode quick = readBody(post("/system/ai/knowledge/test",
				Map.of("id", kbId, "query", "ZX-7788"), adminToken));
			assertThat(quick.path("code").asInt()).as("停用后快捷测试：" + quick).isNotEqualTo(200);
			assertThat(quick.path("msg").asText()).contains("已停用");
		} finally {
			JsonNode on = readBody(post("/system/ai/knowledge/status", Map.of("id", kbId, "status", 1), adminToken));
			assertThat(on.path("code").asInt()).as("启用：" + on.path("msg").asText()).isEqualTo(200);
		}

		assertThat(hitTest("ZX-7788").path("data")).as("启用后又能搜到").hasSize(1);
	}

	private JsonNode hitTest(String query) {
		Map<String, Object> body = new HashMap<>();
		body.put("knowledgeId", kbId);
		body.put("query", query);
		body.put("topK", 5);
		return readBody(post("/system/ai/knowledge/hit-test", body, adminToken));
	}
}
