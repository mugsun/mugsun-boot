package com.mugsun.boot.ai.flow;

import com.mugsun.boot.common.constant.FlowConstants;
import com.mugsun.boot.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AiApprovalNodeListenerMappingTest {

	@Test
	void buildInputUsesMap() {
		Map<String, Object> flow = Map.of("reason", "出差", "days", 2);
		Map<String, Object> in = AiApprovalNodeListener.buildInput(flow, Map.of("reason", "input", "days", "n"));
		assertEquals("出差", in.get("input"));
		assertEquals(2, in.get("n"));
		assertFalse(in.containsKey("reason"));
	}

	@Test
	void buildInputEmptyMapMeansNoInput() {
		// 安全收紧：空/缺省 inputMap 不灌全量流程变量
		Map<String, Object> flow = Map.of("a", 1, "secret", "x");
		assertTrue(AiApprovalNodeListener.buildInput(flow, Map.of()).isEmpty());
		assertTrue(AiApprovalNodeListener.buildInput(flow, null).isEmpty());
	}

	@Test
	void mapOutputsCoercesPassAndDefaults() {
		Map<String, Object> vars = Map.of(
			"ai_summary", "ok",
			"ai_pass", "true",
			"ai_reason", "r",
			"output", "fallback");
		Map<String, Object> m = AiApprovalNodeListener.mapOutputs(vars, Map.of(), 99L);
		assertEquals("ok", m.get(FlowConstants.AI_VAR_SUMMARY));
		assertEquals(Boolean.TRUE, m.get(FlowConstants.AI_VAR_PASS));
		assertEquals("r", m.get(FlowConstants.AI_VAR_REASON));
		assertEquals(99L, m.get(FlowConstants.AI_VAR_RUN_ID));
	}

	@Test
	void mapOutputsCustomMap() {
		Map<String, Object> vars = Map.of("s", "sum", "p", "false", "x", "ext");
		Map<String, Object> m = AiApprovalNodeListener.mapOutputs(vars,
			Map.of("s", "ai_summary", "p", "ai_pass", "x", "ai_ext_score"), 1L);
		assertEquals("sum", m.get("ai_summary"));
		assertEquals(Boolean.FALSE, m.get("ai_pass"));
		assertEquals("ext", m.get("ai_ext_score"));
	}

	@Test
	void mapOutputsDropsDisallowedTargetKeys() {
		Map<String, Object> vars = Map.of("role", "admin", "s", "ok");
		Map<String, Object> m = AiApprovalNodeListener.mapOutputs(vars,
			Map.of("role", "permissionFlag", "s", "ai_summary"), 1L);
		assertFalse(m.containsKey("permissionFlag"));
		assertEquals("ok", m.get("ai_summary"));
		assertEquals(1L, m.get(FlowConstants.AI_VAR_RUN_ID));
	}

	@Test
	void inputSummaryRedactsSecrets() {
		String s = AiApprovalNodeListener.summarizeInput(Map.of(
			"reason", "出差",
			"password", "p@ss",
			"api_token", "tok",
			"Authorization", "Bearer x"));
		assertTrue(s.contains("reason=出差"));
		assertTrue(s.contains("password=***"));
		assertTrue(s.contains("api_token=***"));
		assertTrue(s.contains("Authorization=***"));
		assertFalse(s.contains("p@ss"));
		assertFalse(s.contains("Bearer"));
	}

	@Test
	void workerThreadCarriesTenant() throws Exception {
		AtomicReference<String> seen = new AtomicReference<>();
		String ambientBefore = TenantContext.current();
		String got = AiApprovalNodeListener.executeWithTenantTimeout("T-SEC-1", 5_000L, () -> {
			seen.set(TenantContext.current());
			return TenantContext.current();
		});
		assertEquals("T-SEC-1", got);
		assertEquals("T-SEC-1", seen.get());
		assertEquals(ambientBefore, TenantContext.current());
	}
}
