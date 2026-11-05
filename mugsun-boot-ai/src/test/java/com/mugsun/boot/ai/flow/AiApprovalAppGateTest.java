package com.mugsun.boot.ai.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.ai.entity.AiApp;
import com.mugsun.boot.ai.mapper.AiAppMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class AiApprovalAppGateTest {

	private final ObjectMapper om = new ObjectMapper();

	private AiApp app(String tenant, int status, String type, String dsl) {
		AiApp a = new AiApp();
		a.setId(1L);
		a.setTenantId(tenant);
		a.setStatus(status);
		a.setAppType(type);
		a.setDsl(dsl);
		return a;
	}

	private String safeDsl() {
		return "{\"nodes\":[{\"id\":\"s\",\"type\":\"start\"},{\"id\":\"e\",\"type\":\"end\"}]}";
	}

	@Test
	void missing() {
		AiAppMapper mapper = mock(AiAppMapper.class);
		when(mapper.selectOneById(anyLong())).thenReturn(null);
		var r = AiApprovalAppGate.load(mapper, om, 1L, "000000");
		assertEquals(AiApprovalConstants.ERR_APP_MISSING, r.errorCode());
	}

	@Test
	void disabled() {
		AiAppMapper mapper = mock(AiAppMapper.class);
		when(mapper.selectOneById(1L)).thenReturn(app("000000", 0, "workflow", safeDsl()));
		var r = AiApprovalAppGate.load(mapper, om, 1L, "000000");
		assertEquals(AiApprovalConstants.ERR_APP_DISABLED, r.errorCode());
	}

	@Test
	void typeDenied() {
		AiAppMapper mapper = mock(AiAppMapper.class);
		when(mapper.selectOneById(1L)).thenReturn(app("000000", 1, "text", safeDsl()));
		var r = AiApprovalAppGate.load(mapper, om, 1L, "000000");
		assertEquals(AiApprovalConstants.ERR_APP_TYPE_DENIED, r.errorCode());
	}

	@Test
	void tenantDeniedByField() {
		AiAppMapper mapper = mock(AiAppMapper.class);
		when(mapper.selectOneById(1L)).thenReturn(app("T-B", 1, "workflow", safeDsl()));
		var r = AiApprovalAppGate.load(mapper, om, 1L, "000000");
		assertEquals(AiApprovalConstants.ERR_TENANT_DENIED, r.errorCode());
	}

	@Test
	void capabilityDenied() {
		AiAppMapper mapper = mock(AiAppMapper.class);
		String dsl = "{\"nodes\":[{\"id\":\"x\",\"type\":\"http\"}]}";
		when(mapper.selectOneById(1L)).thenReturn(app("000000", 1, "workflow", dsl));
		var r = AiApprovalAppGate.load(mapper, om, 1L, "000000");
		assertEquals(AiApprovalConstants.ERR_CAPABILITY_DENIED, r.errorCode());
	}


	@Test
	void emptyTenantDenied() {
		AiAppMapper mapper = mock(AiAppMapper.class);
		when(mapper.selectOneById(1L)).thenReturn(app("", 1, "workflow", safeDsl()));
		var r = AiApprovalAppGate.load(mapper, om, 1L, "000000");
		assertEquals(AiApprovalConstants.ERR_TENANT_DENIED, r.errorCode());
	}

	@Test
	void nullTenantDenied() {
		AiAppMapper mapper = mock(AiAppMapper.class);
		when(mapper.selectOneById(1L)).thenReturn(app(null, 1, "workflow", safeDsl()));
		var r = AiApprovalAppGate.load(mapper, om, 1L, "000000");
		assertEquals(AiApprovalConstants.ERR_TENANT_DENIED, r.errorCode());
	}

	@Test
	void okWorkflow() {
		AiAppMapper mapper = mock(AiAppMapper.class);
		when(mapper.selectOneById(1L)).thenReturn(app("000000", 1, "workflow", safeDsl()));
		var r = AiApprovalAppGate.load(mapper, om, 1L, "000000");
		assertTrue(r.ok());
		assertNotNull(r.app());
	}

	@Test
	void okChatflow() {
		AiAppMapper mapper = mock(AiAppMapper.class);
		when(mapper.selectOneById(1L)).thenReturn(app("000000", 1, "chatflow", safeDsl()));
		assertTrue(AiApprovalAppGate.load(mapper, om, 1L, "000000").ok());
	}

	@Test
	void foreachParallelAllowedForApproval() {
		AiAppMapper mapper = mock(AiAppMapper.class);
		String dsl = "{\"nodes\":["
			+ "{\"id\":\"s\",\"type\":\"start\"},"
			+ "{\"id\":\"f\",\"type\":\"foreach\",\"data\":{\"type\":\"foreach\"}},"
			+ "{\"id\":\"p\",\"type\":\"parallel\",\"data\":{\"type\":\"parallel\"}},"
			+ "{\"id\":\"t\",\"type\":\"text\"},"
			+ "{\"id\":\"e\",\"type\":\"end\"}]}";
		when(mapper.selectOneById(1L)).thenReturn(app("000000", 1, "workflow", dsl));
		assertTrue(AiApprovalAppGate.load(mapper, om, 1L, "000000").ok());
	}
}
