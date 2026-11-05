package com.mugsun.boot.ai.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.ai.entity.AiApp;
import com.mugsun.boot.ai.entity.AiAppNodeRun;
import com.mugsun.boot.ai.entity.AiAppRun;
import com.mugsun.boot.ai.mapper.AiAppNodeRunMapper;
import com.mugsun.boot.ai.mapper.AiAppRunMapper;
import com.mugsun.boot.ai.service.AiChannelBizService;
import com.mugsun.boot.ai.service.AiDatasourceBizService;
import com.mugsun.boot.ai.service.AiKnowledgeBizService;
import com.mugsun.boot.ai.service.AiMcpBizService;
import com.mugsun.boot.ai.service.AiModelBizService;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.ai.support.OutboundWebClientFactory;
import com.mugsun.boot.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class AiFlowControlExecutorTest {

	private final ObjectMapper om = new ObjectMapper();
	private final AtomicLong ids = new AtomicLong(1);
	private AiAppRunMapper runMapper;
	private AiAppNodeRunMapper nodeRunMapper;
	private AiLlmClient llmClient;
	private AiFlowExecutor executor;
	private final List<AiAppNodeRun> nodeRuns = new ArrayList<>();

	@BeforeEach
	void setUp() {
		TenantContext.set("000000");
		runMapper = mock(AiAppRunMapper.class);
		nodeRunMapper = mock(AiAppNodeRunMapper.class);
		llmClient = mock(AiLlmClient.class);
		nodeRuns.clear();
		ids.set(1);
		doAnswer(inv -> {
			AiAppRun r = inv.getArgument(0);
			if (r.getId() == null) {
				r.setId(ids.getAndIncrement());
			}
			return 1;
		}).when(runMapper).insert(any());
		doAnswer(inv -> 1).when(runMapper).update(any());
		doAnswer(inv -> {
			AiAppNodeRun n = inv.getArgument(0);
			if (n.getId() == null) {
				n.setId(ids.getAndIncrement());
			}
			synchronized (nodeRuns) {
				nodeRuns.add(n);
			}
			return 1;
		}).when(nodeRunMapper).insert(any());
		doAnswer(inv -> 1).when(nodeRunMapper).update(any());
		executor = new AiFlowExecutor(om, runMapper, nodeRunMapper,
			mock(AiModelBizService.class), llmClient,
			mock(AiKnowledgeBizService.class), mock(AiDatasourceBizService.class),
			mock(AiMcpBizService.class), mock(AiChannelBizService.class),
			WebClient.builder(), mock(OutboundWebClientFactory.class));
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
	}

	private AiApp app(String dsl) {
		AiApp a = new AiApp();
		a.setId(99L);
		a.setTenantId("000000");
		a.setDsl(dsl);
		a.setAppType("workflow");
		a.setStatus(1);
		return a;
	}

	@Test
	void foreachHappyPathAggregatesArray() {
		String dsl = "{\"nodes\":["
			+ "{\"id\":\"s\",\"type\":\"start\",\"data\":{\"type\":\"start\"}},"
			+ "{\"id\":\"f\",\"type\":\"foreach\",\"data\":{\"type\":\"foreach\",\"inputPath\":\"items\","
			+ "\"bodyNodeIds\":[\"t1\"],\"outputVar\":\"foreachResults\",\"aggregate\":\"array\"}},"
			+ "{\"id\":\"t1\",\"type\":\"text\",\"data\":{\"type\":\"text\",\"text\":\"X-{{item}}\"}},"
			+ "{\"id\":\"e\",\"type\":\"end\",\"data\":{\"type\":\"end\"}}"
			+ "],\"edges\":[{\"source\":\"s\",\"target\":\"f\"},{\"source\":\"f\",\"target\":\"e\"}]}";
		Map<String, Object> r = executor.run(app(dsl), Map.of("items", List.of("a", "b")), 1L, "000000");
		assertEquals("success", r.get("status"));
		@SuppressWarnings("unchecked")
		Map<String, Object> vars = (Map<String, Object>) r.get("vars");
		assertEquals(List.of("X-a", "X-b"), vars.get("foreachResults"));
		assertTrue(nodeRuns.stream().anyMatch(n -> n.getNodeId() != null && n.getNodeId().contains("#")));
	}

	@Test
	void foreachInputNotArrayFails() {
		String dsl = "{\"nodes\":["
			+ "{\"id\":\"s\",\"type\":\"start\",\"data\":{\"type\":\"start\"}},"
			+ "{\"id\":\"f\",\"type\":\"foreach\",\"data\":{\"type\":\"foreach\",\"inputPath\":\"items\",\"bodyNodeIds\":[\"t1\"]}},"
			+ "{\"id\":\"t1\",\"type\":\"text\",\"data\":{\"type\":\"text\",\"text\":\"{{item}}\"}},"
			+ "{\"id\":\"e\",\"type\":\"end\",\"data\":{\"type\":\"end\"}}"
			+ "],\"edges\":[{\"source\":\"s\",\"target\":\"f\"},{\"source\":\"f\",\"target\":\"e\"}]}";
		Map<String, Object> r = executor.run(app(dsl), Map.of("items", "not-array"), 1L, "000000");
		assertEquals("failed", r.get("status"));
		assertEquals(AiFlowControlConstants.ERR_FOREACH_INPUT_NOT_ARRAY, r.get("errorCode"));
	}

	@Test
	void foreachTruncatesSuccessfully() throws Exception {
		String dsl = "{\"nodes\":["
			+ "{\"id\":\"s\",\"type\":\"start\",\"data\":{\"type\":\"start\"}},"
			+ "{\"id\":\"f\",\"type\":\"foreach\",\"data\":{\"type\":\"foreach\",\"inputPath\":\"items\",\"maxIterations\":2,"
			+ "\"bodyNodeIds\":[\"t1\"],\"outputVar\":\"foreachResults\"}},"
			+ "{\"id\":\"t1\",\"type\":\"text\",\"data\":{\"type\":\"text\",\"text\":\"{{item}}\"}},"
			+ "{\"id\":\"e\",\"type\":\"end\",\"data\":{\"type\":\"end\"}}"
			+ "],\"edges\":[{\"source\":\"s\",\"target\":\"f\"},{\"source\":\"f\",\"target\":\"e\"}]}";
		Map<String, Object> r = executor.run(app(dsl), Map.of("items", List.of("1", "2", "3", "4")), 1L, "000000");
		assertEquals("success", r.get("status"));
		@SuppressWarnings("unchecked")
		Map<String, Object> vars = (Map<String, Object>) r.get("vars");
		assertEquals(List.of("1", "2"), vars.get("foreachResults"));
		AiAppNodeRun fe = nodeRuns.stream().filter(n -> "foreach".equals(n.getNodeType())
			&& "f".equals(n.getNodeId())).findFirst().orElseThrow();
		JsonNode out = om.readTree(fe.getOutputJson());
		assertTrue(out.path("truncated").asBoolean());
		assertEquals(2, out.path("ranCount").asInt());
		assertEquals(4, out.path("inputCount").asInt());
	}

	@Test
	void foreachNestedRejected() {
		String dsl = "{\"nodes\":["
			+ "{\"id\":\"s\",\"type\":\"start\",\"data\":{\"type\":\"start\"}},"
			+ "{\"id\":\"f\",\"type\":\"foreach\",\"data\":{\"type\":\"foreach\",\"inputPath\":\"items\",\"bodyNodeIds\":[\"inner\"]}},"
			+ "{\"id\":\"inner\",\"type\":\"foreach\",\"data\":{\"type\":\"foreach\",\"inputPath\":\"items\",\"bodyNodeIds\":[\"t1\"]}},"
			+ "{\"id\":\"t1\",\"type\":\"text\",\"data\":{\"type\":\"text\",\"text\":\"{{item}}\"}},"
			+ "{\"id\":\"e\",\"type\":\"end\",\"data\":{\"type\":\"end\"}}"
			+ "],\"edges\":[{\"source\":\"s\",\"target\":\"f\"},{\"source\":\"f\",\"target\":\"e\"}]}";
		Map<String, Object> r = executor.run(app(dsl), Map.of("items", List.of("a")), 1L, "000000");
		assertEquals("failed", r.get("status"));
		assertEquals(AiFlowControlConstants.ERR_FOREACH_BODY_INVALID, r.get("errorCode"));
	}

	@Test
	void foreachBodyMissingRejected() {
		String dsl = "{\"nodes\":["
			+ "{\"id\":\"s\",\"type\":\"start\",\"data\":{\"type\":\"start\"}},"
			+ "{\"id\":\"f\",\"type\":\"foreach\",\"data\":{\"type\":\"foreach\",\"inputPath\":\"items\",\"bodyNodeIds\":[\"missing\"]}},"
			+ "{\"id\":\"e\",\"type\":\"end\",\"data\":{\"type\":\"end\"}}"
			+ "],\"edges\":[{\"source\":\"s\",\"target\":\"f\"},{\"source\":\"f\",\"target\":\"e\"}]}";
		Map<String, Object> r = executor.run(app(dsl), Map.of("items", List.of("a")), 1L, "000000");
		assertEquals("failed", r.get("status"));
		assertEquals(AiFlowControlConstants.ERR_FOREACH_BODY_INVALID, r.get("errorCode"));
	}

	@Test
	void foreachTimeout() {
		when(llmClient.chat(any(), any(), anyInt())).thenAnswer(inv -> {
			Thread.sleep(300);
			return "slow";
		});
		AiModelBizService models = mock(AiModelBizService.class);
		when(models.requireDefaultChat()).thenReturn(new com.mugsun.boot.ai.entity.AiModel());
		executor = new AiFlowExecutor(om, runMapper, nodeRunMapper, models, llmClient,
			mock(AiKnowledgeBizService.class), mock(AiDatasourceBizService.class),
			mock(AiMcpBizService.class), mock(AiChannelBizService.class),
			WebClient.builder(), mock(OutboundWebClientFactory.class));
		String dsl = "{\"nodes\":["
			+ "{\"id\":\"s\",\"type\":\"start\",\"data\":{\"type\":\"start\"}},"
			+ "{\"id\":\"f\",\"type\":\"foreach\",\"data\":{\"type\":\"foreach\",\"inputPath\":\"items\",\"timeoutMs\":50,"
			+ "\"bodyNodeIds\":[\"l1\"]}},"
			+ "{\"id\":\"l1\",\"type\":\"llm\",\"data\":{\"type\":\"llm\",\"prompt\":\"{{item}}\"}},"
			+ "{\"id\":\"e\",\"type\":\"end\",\"data\":{\"type\":\"end\"}}"
			+ "],\"edges\":[{\"source\":\"s\",\"target\":\"f\"},{\"source\":\"f\",\"target\":\"e\"}]}";
		Map<String, Object> r = executor.run(app(dsl), Map.of("items", List.of("a", "b", "c")), 1L, "000000");
		assertEquals("failed", r.get("status"));
		assertEquals(AiFlowControlConstants.ERR_FOREACH_TIMEOUT, r.get("errorCode"));
	}

	@Test
	void parallelWaitAllWritesBothBranches() throws Exception {
		String dsl = "{\"nodes\":["
			+ "{\"id\":\"s\",\"type\":\"start\",\"data\":{\"type\":\"start\"}},"
			+ "{\"id\":\"p\",\"type\":\"parallel\",\"data\":{\"type\":\"parallel\",\"branches\":["
			+ "{\"id\":\"b1\",\"bodyNodeIds\":[\"t1\"],\"outputVar\":\"branchExtract\"},"
			+ "{\"id\":\"b2\",\"bodyNodeIds\":[\"t2\"],\"outputVar\":\"branchSummary\"}]}},"
			+ "{\"id\":\"t1\",\"type\":\"text\",\"data\":{\"type\":\"text\",\"text\":\"EXTRACT\"}},"
			+ "{\"id\":\"t2\",\"type\":\"text\",\"data\":{\"type\":\"text\",\"text\":\"SUMMARY\"}},"
			+ "{\"id\":\"e\",\"type\":\"end\",\"data\":{\"type\":\"end\",\"output\":\"{{branchExtract}}|{{branchSummary}}\"}}"
			+ "],\"edges\":[{\"source\":\"s\",\"target\":\"p\"},{\"source\":\"p\",\"target\":\"e\"}]}";
		Map<String, Object> r = executor.run(app(dsl), Map.of(), 1L, "000000");
		assertEquals("success", r.get("status"));
		assertEquals("EXTRACT|SUMMARY", r.get("output"));
		@SuppressWarnings("unchecked")
		Map<String, Object> vars = (Map<String, Object>) r.get("vars");
		assertEquals("EXTRACT", vars.get("branchExtract"));
		assertEquals("SUMMARY", vars.get("branchSummary"));
		assertNotNull(vars.get("parallelResults"));
		AiAppNodeRun pn = nodeRuns.stream().filter(n -> "parallel".equals(n.getNodeType())).findFirst().orElseThrow();
		JsonNode out = om.readTree(pn.getOutputJson());
		assertFalse(out.path("partial").asBoolean());
	}

	@Test
	void parallelOneBranchFailsStillSuccessPartial() throws Exception {
		when(llmClient.chat(any(), any(), anyInt())).thenThrow(new RuntimeException("llm boom"));
		AiModelBizService models = mock(AiModelBizService.class);
		when(models.requireDefaultChat()).thenReturn(new com.mugsun.boot.ai.entity.AiModel());
		executor = new AiFlowExecutor(om, runMapper, nodeRunMapper, models, llmClient,
			mock(AiKnowledgeBizService.class), mock(AiDatasourceBizService.class),
			mock(AiMcpBizService.class), mock(AiChannelBizService.class),
			WebClient.builder(), mock(OutboundWebClientFactory.class));
		String dsl = "{\"nodes\":["
			+ "{\"id\":\"s\",\"type\":\"start\",\"data\":{\"type\":\"start\"}},"
			+ "{\"id\":\"p\",\"type\":\"parallel\",\"data\":{\"type\":\"parallel\",\"branches\":["
			+ "{\"id\":\"ok\",\"bodyNodeIds\":[\"t1\"],\"outputVar\":\"branchOk\"},"
			+ "{\"id\":\"bad\",\"bodyNodeIds\":[\"l1\"],\"outputVar\":\"branchBad\"}]}},"
			+ "{\"id\":\"t1\",\"type\":\"text\",\"data\":{\"type\":\"text\",\"text\":\"OK\"}},"
			+ "{\"id\":\"l1\",\"type\":\"llm\",\"data\":{\"type\":\"llm\",\"prompt\":\"x\"}},"
			+ "{\"id\":\"e\",\"type\":\"end\",\"data\":{\"type\":\"end\"}}"
			+ "],\"edges\":[{\"source\":\"s\",\"target\":\"p\"},{\"source\":\"p\",\"target\":\"e\"}]}";
		Map<String, Object> r = executor.run(app(dsl), Map.of(), 1L, "000000");
		assertEquals("success", r.get("status"));
		@SuppressWarnings("unchecked")
		Map<String, Object> vars = (Map<String, Object>) r.get("vars");
		assertEquals("OK", vars.get("branchOk"));
		AiAppNodeRun pn = nodeRuns.stream().filter(n -> "parallel".equals(n.getNodeType())).findFirst().orElseThrow();
		JsonNode out = om.readTree(pn.getOutputJson());
		assertTrue(out.path("partial").asBoolean());
	}

	@Test
	void parallelLessThanTwoBranchesInvalid() {
		String dsl = "{\"nodes\":["
			+ "{\"id\":\"s\",\"type\":\"start\",\"data\":{\"type\":\"start\"}},"
			+ "{\"id\":\"p\",\"type\":\"parallel\",\"data\":{\"type\":\"parallel\",\"branches\":["
			+ "{\"id\":\"b1\",\"bodyNodeIds\":[\"t1\"],\"outputVar\":\"x\"}]}},"
			+ "{\"id\":\"t1\",\"type\":\"text\",\"data\":{\"type\":\"text\",\"text\":\"A\"}},"
			+ "{\"id\":\"e\",\"type\":\"end\",\"data\":{\"type\":\"end\"}}"
			+ "],\"edges\":[{\"source\":\"s\",\"target\":\"p\"},{\"source\":\"p\",\"target\":\"e\"}]}";
		Map<String, Object> r = executor.run(app(dsl), Map.of(), 1L, "000000");
		assertEquals("failed", r.get("status"));
		assertEquals(AiFlowControlConstants.ERR_PARALLEL_BRANCH_INVALID, r.get("errorCode"));
	}

	@Test
	void constantsClamp() {
		assertEquals(20, AiFlowControlConstants.clampMaxIterations(0));
		assertEquals(100, AiFlowControlConstants.clampMaxIterations(999));
		assertEquals(8, AiFlowControlConstants.clampMaxBranches(0));
		assertEquals(16, AiFlowControlConstants.clampMaxBranches(99));
		assertEquals(120_000L, AiFlowControlConstants.clampTimeoutMs(0));
		assertEquals(300_000L, AiFlowControlConstants.clampTimeoutMs(999_999L));
		assertTrue(AiFlowControlConstants.syntheticNodeId("p", "0", "c").length() <= 64);
		String longId = AiFlowControlConstants.syntheticNodeId("p".repeat(40), "branchLong", "c".repeat(40));
		assertTrue(longId.length() <= 64, longId);
	}
}
