package com.mugsun.boot.ai.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.ai.mapper.AiAppMapper;
import com.mugsun.boot.system.service.HandlerSelectService;
import org.dromara.warm.flow.core.entity.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BOOT-123：流程引擎的表不写租户，AI 预审节点要按发起人所属租户运行；发起人也查不到租户时才拒绝。
 */
class AiApprovalNodeListenerTenantTest {

	private final HandlerSelectService selectService = mock(HandlerSelectService.class);
	private final AiApprovalNodeListener listener = new AiApprovalNodeListener(
		new ObjectMapper(), mock(AiAppMapper.class), mock(AiFlowExecutor.class), selectService);

	@Test
	@DisplayName("实例上没有租户：按发起人所属租户算")
	void instanceWithoutTenantUsesTheInitiatorsTenant() {
		when(selectService.tenantOfUser("42")).thenReturn("000000");

		assertEquals("000000", listener.instanceTenant(instance(null, "42")));
		assertEquals("000000", listener.instanceTenant(instance("  ", "42")));
	}

	@Test
	@DisplayName("引擎给实例写了租户：用实例自己的，不再查发起人")
	void tenantWrittenByTheEngineWins() {
		assertEquals("T100", listener.instanceTenant(instance(" T100 ", "42")));
		verify(selectService, never()).tenantOfUser("42");
	}

	@Test
	@DisplayName("发起人查不到或没有租户：没有结果，调用方据此拒绝运行")
	void unknownInitiatorMeansNoTenant() {
		when(selectService.tenantOfUser("7")).thenReturn(null);
		when(selectService.tenantOfUser("8")).thenReturn(" ");

		assertNull(listener.instanceTenant(instance(null, "7")));
		assertNull(listener.instanceTenant(instance(null, "8")));
		assertNull(listener.instanceTenant(instance(null, null)));
	}

	private Instance instance(String tenant, String createBy) {
		Instance ins = mock(Instance.class);
		when(ins.getTenantId()).thenReturn(tenant);
		when(ins.getCreateBy()).thenReturn(createBy);
		return ins;
	}
}
