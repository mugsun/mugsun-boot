package com.mugsun.boot.ai.service;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiKnowledge;
import com.mugsun.boot.ai.entity.AiKnowledgeAsset;
import com.mugsun.boot.ai.entity.AiKnowledgeSegment;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.boot.ai.mapper.AiKnowledgeAssetMapper;
import com.mugsun.boot.ai.mapper.AiKnowledgeMapper;
import com.mugsun.boot.ai.mapper.AiKnowledgeSegmentMapper;
import com.mugsun.boot.ai.support.AiKbVectorStore;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * BOOT-091：知识库检索不再静默失效。
 * 分段表用内存列表代替，查询按 SQL 里的条件、排序和条数取数，所以翻页条件写错时测试会失败。
 */
class AiKnowledgeRetrievalTest {

	private static final long KB_ID = 9L;
	private static final long ASSET_ID = 31L;

	private final List<AiKnowledgeSegment> table = new ArrayList<>();
	private AiKnowledgeSegmentMapper segmentMapper;
	private AiModelBizService modelBizService;
	private AiLlmClient llmClient;
	private AiKbVectorStore vectorStore;
	private AiKnowledgeBizService service;
	private AiKnowledge kb;
	private AiKnowledgeAsset asset;
	private AiModel embeddingModel;

	@BeforeEach
	void setUp() {
		AiKnowledgeMapper knowledgeMapper = mock(AiKnowledgeMapper.class);
		AiKnowledgeAssetMapper assetMapper = mock(AiKnowledgeAssetMapper.class);
		segmentMapper = mock(AiKnowledgeSegmentMapper.class);
		modelBizService = mock(AiModelBizService.class);
		llmClient = mock(AiLlmClient.class);
		vectorStore = mock(AiKbVectorStore.class);
		service = new AiKnowledgeBizService(mock(AiModuleService.class), knowledgeMapper, assetMapper, segmentMapper,
			modelBizService, llmClient, vectorStore, null, null, null, null, null);

		kb = new AiKnowledge();
		kb.setId(KB_ID);
		kb.setStatus(AiConstants.STATUS_ENABLE);
		kb.setRetrievalMode("keyword");
		when(knowledgeMapper.selectOneById(KB_ID)).thenReturn(kb);

		asset = new AiKnowledgeAsset();
		asset.setId(ASSET_ID);
		asset.setKnowledgeId(KB_ID);
		asset.setVectorStatus("pending");
		when(assetMapper.selectListByQuery(any(QueryWrapper.class))).thenReturn(List.of(asset));
		when(assetMapper.selectOneById(ASSET_ID)).thenReturn(asset);

		embeddingModel = new AiModel();
		embeddingModel.setId(7L);
		embeddingModel.setModelName("embed-demo");
		embeddingModel.setModelType(AiConstants.MODEL_TYPE_EMBEDDING);

		when(segmentMapper.selectListByQuery(any(QueryWrapper.class))).thenAnswer(inv -> select(inv.getArgument(0)));
		when(segmentMapper.selectOneById(anyLong())).thenAnswer(inv -> {
			long id = inv.getArgument(0);
			return table.stream().filter(s -> s.getId() == id).findFirst().orElse(null);
		});
	}

	@Test
	void keywordSearchFindsASegmentFarBeyondTheFirst500() {
		seed(1203, i -> "第 " + i + " 条：日常办公说明");
		table.get(1149).setContent("报销单编号 ZX-7788 的审批规则：金额超过五千元要部门负责人签字");

		List<Map<String, Object>> hits = service.hitTest(KB_ID, "ZX-7788", 5);

		assertThat(hits).extracting(h -> h.get("segmentId")).containsExactly(1150L);
		// 1203 段分三页读完：500 + 500 + 203
		verify(segmentMapper, times(3)).selectListByQuery(any(QueryWrapper.class));
	}

	@Test
	void keywordSearchKeepsTheBestHitsFromEveryPage() {
		seed(700, i -> "差旅安排说明，第 " + i + " 条");
		table.get(649).setContent("差旅报销标准：住宿每晚不超过四百元");

		List<Map<String, Object>> hits = service.hitTest(KB_ID, "差旅 报销", 3);

		// 两个词都命中的排第一；其余只命中一个词、分数相同，按 id 从小到大
		assertThat(hits).extracting(h -> h.get("segmentId")).containsExactly(650L, 1L, 2L);
	}

	@Test
	void exactlyOneFullPageStillEndsTheScan() {
		seed(AiKnowledgeBizService.KEYWORD_PAGE_SIZE, i -> "制度条款 " + i);

		List<Map<String, Object>> hits = service.hitTest(KB_ID, "条款 500", 2);

		assertThat(hits.get(0)).containsEntry("segmentId", 500L);
		// 满页之后再查一次，拿到空页才停
		verify(segmentMapper, times(2)).selectListByQuery(any(QueryWrapper.class));
	}

	@Test
	void disabledKnowledgeBaseIsRefusedInHitTestAndSkippedAtRuntime() {
		seed(3, i -> "差旅报销标准 " + i);
		kb.setStatus(AiConstants.STATUS_DISABLE);

		assertThatThrownBy(() -> service.hitTest(KB_ID, "差旅", 3))
			.isInstanceOf(ServiceException.class).hasMessageContaining("已停用");
		assertThatThrownBy(() -> service.test(KB_ID, "差旅"))
			.isInstanceOf(ServiceException.class).hasMessageContaining("已停用");
		assertThat(service.retrieve(KB_ID, "差旅", 3)).isEmpty();
		verifyNoInteractions(segmentMapper, vectorStore, llmClient);

		kb.setStatus(AiConstants.STATUS_ENABLE);
		assertThat(service.retrieve(KB_ID, "差旅", 3)).hasSize(3);
		assertThat(service.hitTest(KB_ID, "差旅", 3)).hasSize(3);
	}

	@Test
	void embeddingFailureMarksSegmentsAndAssetFailedWithoutWritingHashVectors() {
		seed(20, i -> "制度条款 " + i);
		when(vectorStore.isVectorAvailable()).thenReturn(true);
		when(modelBizService.requireDefaultEmbedding()).thenReturn(embeddingModel);
		when(llmClient.embedBatch(any(), anyList())).thenThrow(new ServiceException(
			"向量化失败: 401 Unauthorized from POST https://embed.example.invalid/v1/embeddings?key=abc"));

		Map<String, Object> result = service.vectorize(KB_ID, false);

		// 第一批失败后，后面两批不再调模型
		verify(llmClient, times(1)).embedBatch(any(), anyList());
		verify(vectorStore, never()).upsert(any(), any(), any(), any(), any());
		verify(vectorStore, times(20)).deleteBySegment(anyLong());
		assertThat(table).allMatch(s -> "failed".equals(s.getVectorStatus()));
		assertThat(asset.getVectorStatus()).isEqualTo("failed");
		assertThat(asset.getFailReason()).contains("向量模型调用失败").doesNotContain("401", "example.invalid", "key=");
		assertThat(result).containsEntry("ok", false).containsEntry("count", 0)
			.containsEntry("failed", 20).containsEntry("success", 0);
		assertThat(String.valueOf(result.get("message"))).contains("向量模型调用失败").doesNotContain("401");
	}

	@Test
	void retryAfterTheModelRecoversClearsTheFailure() {
		seed(20, i -> "制度条款 " + i);
		table.forEach(s -> s.setVectorStatus("failed"));
		asset.setVectorStatus("failed");
		asset.setFailReason("向量模型调用失败");
		when(vectorStore.isVectorAvailable()).thenReturn(true);
		when(modelBizService.requireDefaultEmbedding()).thenReturn(embeddingModel);
		when(llmClient.embedBatch(any(), anyList())).thenAnswer(inv -> vectors(inv.<List<String>>getArgument(1).size()));

		Map<String, Object> result = service.vectorize(KB_ID, false);

		// 20 段按 8 段一批：8 + 8 + 4
		verify(llmClient, times(3)).embedBatch(any(), anyList());
		verify(vectorStore, times(20)).upsert(any(), any(), any(), any(float[].class), any());
		assertThat(table).allMatch(s -> "success".equals(s.getVectorStatus()));
		assertThat(asset.getVectorStatus()).isEqualTo("success");
		assertThat(asset.getFailReason()).isEmpty();
		assertThat(result).containsEntry("ok", true).containsEntry("count", 20)
			.containsEntry("failed", 0).containsEntry("success", 20).doesNotContainKey("message");
	}

	@Test
	void wrongNumberOfVectorsIsAFailure() {
		seed(8, i -> "制度条款 " + i);
		when(vectorStore.isVectorAvailable()).thenReturn(true);
		when(modelBizService.requireDefaultEmbedding()).thenReturn(embeddingModel);
		when(llmClient.embedBatch(any(), anyList())).thenReturn(vectors(7));

		Map<String, Object> result = service.vectorize(KB_ID, false);

		verify(vectorStore, never()).upsert(any(), any(), any(), any(), any());
		assertThat(table).allMatch(s -> "failed".equals(s.getVectorStatus()));
		assertThat(asset.getVectorStatus()).isEqualTo("failed");
		assertThat(asset.getFailReason()).contains("条数");
		assertThat(result).containsEntry("ok", false).containsEntry("failed", 8);
	}

	@Test
	void deletedEmbeddingModelIsReportedInsteadOfSilentlyUsingHashVectors() {
		seed(3, i -> "制度条款 " + i);
		kb.setEmbeddingModelId(99L);
		kb.setRetrievalMode("hybrid");
		when(vectorStore.isVectorAvailable()).thenReturn(true);
		when(modelBizService.requireRaw(99L)).thenThrow(new ServiceException(AiConstants.MSG_MODEL_MISSING));

		service.vectorize(KB_ID, false);
		List<Map<String, Object>> hits = service.hitTest(KB_ID, "制度", 3);

		verifyNoInteractions(llmClient);
		verify(vectorStore, never()).upsert(any(), any(), any(), any(), any());
		verify(vectorStore, never()).search(any(), any(), anyInt(), any());
		assertThat(table).allMatch(s -> "failed".equals(s.getVectorStatus()));
		assertThat(asset.getFailReason()).contains("已经不存在");
		// 向量这条路走不了，关键词照常能搜到
		assertThat(hits).hasSize(3);
	}

	@Test
	void withoutAnyEmbeddingModelLocalHashIsUsedForBothStoringAndQuerying() {
		seed(3, i -> "制度条款 " + i);
		kb.setRetrievalMode("hybrid");
		when(vectorStore.isVectorAvailable()).thenReturn(true);
		when(modelBizService.requireDefaultEmbedding()).thenThrow(new ServiceException("请先配置并激活默认向量模型"));

		Map<String, Object> result = service.vectorize(KB_ID, false);
		service.hitTest(KB_ID, "制度", 3);

		verifyNoInteractions(llmClient);
		verify(vectorStore, times(3)).upsert(any(), any(), any(), any(float[].class), any());
		verify(vectorStore).search(any(), any(float[].class), anyInt(), any());
		assertThat(table).allMatch(s -> "success".equals(s.getVectorStatus()));
		assertThat(result).containsEntry("ok", true).containsEntry("count", 3);
	}

	@Test
	void queryEmbeddingFailureUsesKeywordHitsOnly() {
		seed(3, i -> "制度条款 " + i);
		kb.setRetrievalMode("vector");
		when(vectorStore.isVectorAvailable()).thenReturn(true);
		when(modelBizService.requireDefaultEmbedding()).thenReturn(embeddingModel);
		when(llmClient.embed(any(), anyString())).thenThrow(new ServiceException("向量化失败: timeout"));

		List<Map<String, Object>> hits = service.retrieve(KB_ID, "制度", 3);

		verify(vectorStore, never()).search(any(), any(), anyInt(), any());
		assertThat(hits).hasSize(3);
		assertThat(hits).extracting(h -> h.get("source")).containsOnly("keyword");
	}

	@Test
	void editingASegmentThatFailsToEmbedMarksItsAssetFailed() {
		seed(2, i -> "制度条款 " + i);
		table.forEach(s -> s.setVectorStatus("success"));
		asset.setVectorStatus("success");
		when(vectorStore.isVectorAvailable()).thenReturn(true);
		when(modelBizService.requireDefaultEmbedding()).thenReturn(embeddingModel);
		when(llmClient.embedBatch(any(), anyList())).thenThrow(new ServiceException("向量化失败: timeout"));
		AiKnowledgeSegment body = new AiKnowledgeSegment();
		body.setId(2L);
		body.setContent("改过的制度条款");

		AiKnowledgeSegment saved = service.saveSegment(body);

		assertThat(saved.getVectorStatus()).isEqualTo("failed");
		assertThat(table.get(0).getVectorStatus()).isEqualTo("success");
		assertThat(asset.getVectorStatus()).isEqualTo("failed");
		assertThat(asset.getFailReason()).contains("向量模型调用失败");
		verify(vectorStore).deleteBySegment(2L);
	}

	private static List<float[]> vectors(int n) {
		List<float[]> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			out.add(new float[]{0.1f, 0.2f, 0.3f});
		}
		return out;
	}

	/** 往表里放 n 个启用的分段，id 从 1 到 n，都属于同一份资料。 */
	private void seed(int n, IntFunction<String> content) {
		for (int i = 1; i <= n; i++) {
			AiKnowledgeSegment s = new AiKnowledgeSegment();
			s.setId((long) i);
			s.setKnowledgeId(KB_ID);
			s.setAssetId(ASSET_ID);
			s.setTenantId("000000");
			s.setSeq(i);
			s.setContent(content.apply(i));
			s.setVectorStatus("pending");
			s.setEnabled(AiConstants.FLAG_YES);
			table.add(s);
		}
	}

	/** 按查询里的知识库、资料、启用状态、id 下限和条数，从内存列表里取数，按 id 从小到大。 */
	private List<AiKnowledgeSegment> select(QueryWrapper query) {
		String sql = query.toSQL().replace("`", "").replace("\"", "");
		String kbId = first(sql, "\\bknowledge_id\\s*=\\s*'?(\\d+)");
		String assetId = first(sql, "\\basset_id\\s*=\\s*'?(\\d+)");
		String enabled = first(sql, "\\benabled\\s*=\\s*'?(\\d+)");
		String after = first(sql, "\\bid\\s*>\\s*'?(\\d+)");
		String limit = first(sql, "(?i)\\bLIMIT\\s+(\\d+)");
		List<AiKnowledgeSegment> rows = new ArrayList<>();
		for (AiKnowledgeSegment s : table) {
			if (kbId != null && s.getKnowledgeId() != Long.parseLong(kbId)) {
				continue;
			}
			if (assetId != null && s.getAssetId() != Long.parseLong(assetId)) {
				continue;
			}
			if (enabled != null && s.getEnabled() != Integer.parseInt(enabled)) {
				continue;
			}
			if (after != null && s.getId() <= Long.parseLong(after)) {
				continue;
			}
			rows.add(s);
		}
		rows.sort(Comparator.comparing(AiKnowledgeSegment::getId));
		if (limit != null && rows.size() > Integer.parseInt(limit)) {
			return new ArrayList<>(rows.subList(0, Integer.parseInt(limit)));
		}
		return rows;
	}

	private static String first(String text, String regex) {
		Matcher m = Pattern.compile(regex).matcher(text);
		return m.find() ? m.group(1) : null;
	}
}
