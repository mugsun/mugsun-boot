package com.mugsun.boot.ai.flow;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通知节点：流程变量默认不跟着消息发出去，只带节点点了名的；消息没送达就算节点失败 */
class AiFlowNoticeNodeTest {

	private static final Map<String, Object> DELIVERED = Map.of("ok", true, "message", "已发出");
	private static final Map<String, Object> INPUT = Map.of("orderNo", "A-1", "amount", 12, "idCard", "440100199001011234");

	private final ObjectMapper om = new ObjectMapper();
	private final AtomicLong ids = new AtomicLong(1);
	private AiChannelBizService channels;
	private AiFlowExecutor executor;

	@BeforeEach
	void setUp() {
		TenantContext.set("000000");
		AiAppRunMapper runMapper = mock(AiAppRunMapper.class);
		AiAppNodeRunMapper nodeRunMapper = mock(AiAppNodeRunMapper.class);
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
			return 1;
		}).when(nodeRunMapper).insert(any());
		doAnswer(inv -> 1).when(nodeRunMapper).update(any());

		channels = mock(AiChannelBizService.class);
		when(channels.send(any(), anyString(), anyMap())).thenReturn(DELIVERED);
		executor = new AiFlowExecutor(om, runMapper, nodeRunMapper,
			mock(AiModelBizService.class), mock(AiLlmClient.class),
			mock(AiKnowledgeBizService.class), mock(AiDatasourceBizService.class),
			mock(AiMcpBizService.class), channels,
			WebClient.builder(), mock(OutboundWebClientFactory.class));
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
	}

	private Map<String, Object> run(Map<String, Object> noticeData, Map<String, Object> input) throws Exception {
		Map<String, Object> data = new HashMap<>(noticeData);
		data.put("type", "notice");
		String dsl = om.writeValueAsString(Map.of(
			"nodes", List.of(
				Map.of("id", "s", "type", "start", "data", Map.of("type", "start")),
				Map.of("id", "n", "type", "notice", "data", data),
				Map.of("id", "e", "type", "end", "data", Map.of("type", "end"))),
			"edges", List.of(Map.of("source", "s", "target", "n"), Map.of("source", "n", "target", "e"))));
		AiApp app = new AiApp();
		app.setId(99L);
		app.setTenantId("000000");
		app.setDsl(dsl);
		app.setAppType("workflow");
		app.setStatus(1);
		return executor.run(app, input, 1L, "000000");
	}

	@Test
	void flowVariablesStayInsideUnlessTheNodeNamesThem() throws Exception {
		Map<String, Object> result = run(Map.of("channelId", "5", "content", "订单 {{orderNo}} 已审完"), INPUT);

		assertEquals("success", result.get("status"), String.valueOf(result.get("errorMsg")));
		verify(channels).send(5L, "订单 A-1 已审完", Map.of());
	}

	@Test
	void namedVariablesAreSentAndUnknownNamesAreSkipped() throws Exception {
		Map<String, Object> result = run(
			Map.of("channelId", 5, "content", "审完了", "vars", List.of("orderNo", "noSuchVar")), INPUT);

		assertEquals("success", result.get("status"), String.valueOf(result.get("errorMsg")));
		verify(channels).send(5L, "审完了", Map.of("orderNo", "A-1"));
	}

	@Test
	void namesCanAlsoBeGivenAsOneCommaSeparatedString() throws Exception {
		run(Map.of("channelId", 5, "content", "审完了", "vars", "orderNo， amount"), INPUT);

		verify(channels).send(5L, "审完了", Map.of("orderNo", "A-1", "amount", 12));
	}

	@Test
	void anUndeliveredNotificationFailsTheNodeWithTheReason() throws Exception {
		String reason = "企业微信没有收下这条消息：invalid webhook url（错误码 93000）";
		when(channels.send(any(), anyString(), anyMap())).thenReturn(Map.of("ok", false, "message", reason));

		Map<String, Object> result = run(Map.of("channelId", 5, "content", "审完了"), INPUT);

		assertEquals("failed", result.get("status"));
		assertEquals(reason, result.get("errorMsg"));
	}
}
