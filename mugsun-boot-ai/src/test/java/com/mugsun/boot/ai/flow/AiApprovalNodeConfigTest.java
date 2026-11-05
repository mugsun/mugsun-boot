package com.mugsun.boot.ai.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiApprovalNodeConfigTest {

	private final ObjectMapper om = new ObjectMapper();

	@Test
	void defaults() throws Exception {
		AiApprovalNodeConfig c = AiApprovalNodeConfig.parse(om, "{\"aiFlowId\":7}");
		assertEquals(7L, c.aiFlowId());
		assertEquals(AiApprovalConstants.DEFAULT_TIMEOUT_MS, c.timeoutMs());
		assertEquals(AiApprovalConstants.ON_FAILURE_REJECT_LAST, c.onFailure());
		assertTrue(c.inputMap().isEmpty());
	}

	@Test
	void full() throws Exception {
		String ext = "{\"aiFlowId\":\"9\",\"timeoutMs\":1000,\"onFailure\":\"stay\","
			+ "\"inputMap\":{\"a\":\"b\"},\"outputMap\":{\"x\":\"y\"}}";
		AiApprovalNodeConfig c = AiApprovalNodeConfig.parse(om, ext);
		assertEquals(9L, c.aiFlowId());
		assertEquals(1000L, c.timeoutMs());
		assertEquals("stay", c.onFailure());
		assertEquals("b", c.inputMap().get("a"));
		assertEquals("y", c.outputMap().get("x"));
	}

	@Test
	void timeoutHardCap() throws Exception {
		AiApprovalNodeConfig c = AiApprovalNodeConfig.parse(om, "{\"aiFlowId\":1,\"timeoutMs\":999999}");
		assertEquals(AiApprovalConstants.MAX_TIMEOUT_MS, c.timeoutMs());
	}
}
