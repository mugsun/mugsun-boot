package com.mugsun.boot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BOOT-089：分段参数、段数、分段内容都有上限；被拒绝的上传不留资料和分段，被拒绝的重新分段不动原来的分段。
 * 每个用例用自己的知识库，互不影响。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiKnowledgeSegmentLimitsApiTest extends AbstractIntegrationTest {

	private static final String TS = String.valueOf(System.currentTimeMillis());
	private static final String KB = "/system/ai/knowledge";
	private static final String TWO_PARAGRAPHS = "第一条：报销单须在费用发生后 30 天内提交。\n\n第二条：单笔超过 5000 元须部门负责人审批。";

	private final List<Long> knowledgeIds = new ArrayList<>();
	private String adminToken;

	@BeforeAll
	void login() {
		adminToken = loginAdmin();
	}

	@AfterAll
	void removeKnowledge() {
		for (Long id : knowledgeIds) {
			post(KB + "/remove?ids=" + id, null, adminToken);
		}
	}

	@Test
	@DisplayName("贴正文上传：重叠超过长度一半、长度越界、分段方式不认识、分隔符过长都被拒绝，不留资料")
	void pastedUploadWithBadOptionsIsRejectedAndStoresNothing() {
		long kb = knowledge("opts");
		String text = "字".repeat(800);
		assertThat(upload(kb, text, "length", 100, 500, null).path("msg").asText()).contains("0 到 50");
		assertThat(upload(kb, text, "length", 500, 500, null).path("msg").asText()).contains("0 到 250");
		assertThat(upload(kb, text, "length", 32, 0, null).path("msg").asText()).contains("100 到 4000");
		assertThat(upload(kb, text, "length", 4001, 0, null).path("msg").asText()).contains("100 到 4000");
		assertThat(upload(kb, text, "regex", 500, 50, null).path("msg").asText()).contains("分段方式");
		assertThat(upload(kb, text, "symbol", 500, 50, "=".repeat(17)).path("msg").asText()).contains("分隔符");
		assertThat(assets(kb)).as("被拒绝的上传不留资料").isEmpty();
		assertThat(segments(kb)).isEmpty();

		// 同一份正文、范围内的设置：按 100 字一段、重叠 50，800 字切成 15 段
		JsonNode ok = upload(kb, text, "length", 100, 50, null);
		assertThat(ok.path("code").asInt()).as(ok.path("msg").asText()).isEqualTo(200);
		assertThat(ok.path("data").path("segmentCount").asInt()).isEqualTo(15);
		assertThat(segments(kb)).hasSize(15);
	}

	@Test
	@DisplayName("传文件：分段参数走同一套检查，被拒绝时不留资料")
	void fileUploadUsesTheSameChecks() {
		long kb = knowledge("file");
		HttpHeaders headers = authHeaders(adminToken);
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", new ByteArrayResource("%PDF-1.4 fake".getBytes(StandardCharsets.US_ASCII)) {
			@Override
			public String getFilename() {
				return "rules.pdf";
			}
		});
		body.add("knowledgeId", String.valueOf(kb));
		body.add("segmentType", "length");
		body.add("segmentLength", "100");
		body.add("segmentOverlap", "500");
		JsonNode resp = readBody(rest.exchange(KB + "/asset/upload-file", HttpMethod.POST,
			new HttpEntity<>(body, headers), String.class));
		assertThat(resp.path("code").asInt()).isNotEqualTo(200);
		assertThat(resp.path("msg").asText()).contains("0 到 50");
		assertThat(assets(kb)).as("参数不对时连解析失败的资料行也不留").isEmpty();
	}

	@Test
	@DisplayName("段数超过上限：整份拒绝，不留资料和半截分段")
	void tooManySegmentsIsRejectedAndStoresNothing() {
		long kb = knowledge("many");
		JsonNode resp = upload(kb, "一行\n".repeat(5001), "newline", 500, 50, null);
		assertThat(resp.path("code").asInt()).isNotEqualTo(200);
		assertThat(resp.path("msg").asText()).contains("超过 5000 段");
		assertThat(assets(kb)).isEmpty();
		assertThat(segments(kb)).isEmpty();
	}

	@Test
	@DisplayName("改分段内容：不能为空，不能超过 4000 字；被拒绝时内容不变")
	void segmentContentIsBounded() {
		long kb = knowledge("edit");
		assertThat(upload(kb, TWO_PARAGRAPHS, "paragraph", 500, 50, null).path("code").asInt()).isEqualTo(200);
		JsonNode seg = segments(kb).get(0);
		long id = seg.path("id").asLong();
		String original = seg.path("content").asText();

		assertThat(edit(id, "  \n ").path("msg").asText()).contains("不能为空");
		JsonNode tooLong = edit(id, "长".repeat(4001));
		assertThat(tooLong.path("code").asInt()).isNotEqualTo(200);
		assertThat(tooLong.path("msg").asText()).contains("4000 字以内").contains("4001");
		assertThat(segments(kb).get(0).path("content").asText()).isEqualTo(original);

		JsonNode ok = edit(id, "  " + "长".repeat(4000) + "\n");
		assertThat(ok.path("code").asInt()).as(ok.path("msg").asText()).isEqualTo(200);
		assertThat(segments(kb).get(0).path("content").asText()).isEqualTo("长".repeat(4000));
	}

	@Test
	@DisplayName("重新分段：库里的旧参数收进范围；段数超限被拒绝时原来的分段还在")
	void resegmentClampsStoredOptionsAndKeepsOldSegmentsWhenRejected() {
		long kb = knowledge("reseg");
		JsonNode uploaded = upload(kb, "字".repeat(800), "length", 400, 0, null);
		assertThat(uploaded.path("code").asInt()).as(uploaded.path("msg").asText()).isEqualTo(200);
		long assetId = uploaded.path("data").path("id").asLong();
		assertThat(segments(kb)).hasSize(2);

		// 早先没有校验时存进去的参数：长度 1、重叠 500，按老算法是每次只挪 1 个字
		jdbc.update("update ai_knowledge_asset set segment_length = 1, segment_overlap = 500 where id = ?", assetId);
		JsonNode again = readBody(post(KB + "/asset/resegment?id=" + assetId, null, adminToken));
		assertThat(again.path("code").asInt()).as(again.path("msg").asText()).isEqualTo(200);
		assertThat(again.path("data").path("segmentLength").asInt()).isEqualTo(100);
		assertThat(again.path("data").path("segmentOverlap").asInt()).isEqualTo(50);
		assertThat(segments(kb)).as("按 100 字一段、重叠 50").hasSize(15);

		List<Long> before = segmentIds(kb);
		jdbc.update("update ai_knowledge_asset set segment_type = 'newline', content_text = ? where id = ?",
			"一行\n".repeat(5001), assetId);
		JsonNode rejected = readBody(post(KB + "/asset/resegment?id=" + assetId, null, adminToken));
		assertThat(rejected.path("code").asInt()).isNotEqualTo(200);
		assertThat(rejected.path("msg").asText()).contains("超过 5000 段");
		assertThat(segmentIds(kb)).as("被拒绝的重新分段不动原来的分段").isEqualTo(before);
	}

	private long knowledge(String tag) {
		Map<String, Object> kb = new HashMap<>();
		kb.put("name", "it-kblimit-" + tag + "-" + TS);
		kb.put("description", "集成测试：分段上限");
		JsonNode created = readBody(post(KB + "/submit", kb, adminToken));
		assertThat(created.path("code").asInt()).as("建知识库：" + created.path("msg").asText()).isEqualTo(200);
		long id = created.path("data").path("id").asLong();
		knowledgeIds.add(id);
		return id;
	}

	private JsonNode upload(long kb, String text, String type, Integer length, Integer overlap, String symbol) {
		Map<String, Object> asset = new HashMap<>();
		asset.put("knowledgeId", kb);
		asset.put("fileName", "正文.txt");
		asset.put("fileType", "text");
		asset.put("contentText", text);
		asset.put("segmentType", type);
		asset.put("segmentLength", length);
		asset.put("segmentOverlap", overlap);
		asset.put("segmentSymbol", symbol);
		return readBody(post(KB + "/asset/upload", asset, adminToken));
	}

	private JsonNode edit(long segmentId, String content) {
		Map<String, Object> body = new HashMap<>();
		body.put("id", segmentId);
		body.put("content", content);
		return readBody(post(KB + "/segment/submit", body, adminToken));
	}

	private JsonNode assets(long kb) {
		JsonNode resp = readBody(get(KB + "/asset/list?knowledgeId=" + kb, adminToken));
		assertThat(resp.path("code").asInt()).as(resp.path("msg").asText()).isEqualTo(200);
		return resp.path("data");
	}

	private JsonNode segments(long kb) {
		JsonNode resp = readBody(get(KB + "/segment/list?knowledgeId=" + kb, adminToken));
		assertThat(resp.path("code").asInt()).as(resp.path("msg").asText()).isEqualTo(200);
		return resp.path("data");
	}

	private List<Long> segmentIds(long kb) {
		List<Long> ids = new ArrayList<>();
		for (JsonNode seg : segments(kb)) {
			ids.add(seg.path("id").asLong());
		}
		return ids;
	}
}
