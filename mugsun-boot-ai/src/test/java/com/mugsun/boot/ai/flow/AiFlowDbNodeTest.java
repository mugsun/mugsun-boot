package com.mugsun.boot.ai.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiApp;
import com.mugsun.boot.ai.entity.AiAppNodeRun;
import com.mugsun.boot.ai.entity.AiAppRun;
import com.mugsun.boot.ai.entity.AiDatasource;
import com.mugsun.boot.ai.mapper.AiAppNodeRunMapper;
import com.mugsun.boot.ai.mapper.AiAppRunMapper;
import com.mugsun.boot.ai.service.AiChannelBizService;
import com.mugsun.boot.ai.service.AiDatasourceBizService;
import com.mugsun.boot.ai.service.AiKnowledgeBizService;
import com.mugsun.boot.ai.service.AiMcpBizService;
import com.mugsun.boot.ai.service.AiModelBizService;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.ai.support.OutboundWebClientFactory;
import com.mugsun.boot.ai.support.SqlReadOnlyChecker;
import com.mugsun.boot.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

/** 数据库节点：变量只能当值绑定，查询先过只读检查和表白名单 */
class AiFlowDbNodeTest {

	private static final List<Map<String, Object>> ROWS = List.of(Map.of("id", 1, "region", "华南"));

	private final ObjectMapper om = new ObjectMapper();
	private final AtomicLong ids = new AtomicLong(1);
	private AiDatasourceBizService datasources;
	private AiDatasource datasource;
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

		datasource = new AiDatasource();
		datasource.setTableWhitelist("wdx074_sales, wdx074_orders");
		datasources = mock(AiDatasourceBizService.class);
		when(datasources.requireRaw(7L)).thenReturn(datasource);
		when(datasources.runQuery(any(), anyString(), anyList(), anyInt(), anyInt())).thenReturn(ROWS);

		executor = new AiFlowExecutor(om, runMapper, nodeRunMapper,
			mock(AiModelBizService.class), mock(AiLlmClient.class),
			mock(AiKnowledgeBizService.class), datasources,
			mock(AiMcpBizService.class), mock(AiChannelBizService.class),
			WebClient.builder(), mock(OutboundWebClientFactory.class));
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
	}

	private Map<String, Object> run(Map<String, Object> dbData, Map<String, Object> input) throws Exception {
		Map<String, Object> data = new HashMap<>(dbData);
		data.put("type", "db");
		data.put("datasourceId", 7);
		String dsl = om.writeValueAsString(Map.of(
			"nodes", List.of(
				Map.of("id", "s", "type", "start", "data", Map.of("type", "start")),
				Map.of("id", "d", "type", "db", "data", data),
				Map.of("id", "e", "type", "end", "data", Map.of("type", "end"))),
			"edges", List.of(Map.of("source", "s", "target", "d"), Map.of("source", "d", "target", "e"))));
		AiApp app = new AiApp();
		app.setId(99L);
		app.setTenantId("000000");
		app.setDsl(dsl);
		app.setAppType("workflow");
		app.setStatus(1);
		return executor.run(app, input, 1L, "000000");
	}

	private static SqlReadOnlyChecker.Param param(Object value, boolean quoted) {
		return new SqlReadOnlyChecker.Param(value, quoted);
	}

	private void assertRejected(String expectedMessage, Map<String, Object> result) {
		assertEquals("failed", result.get("status"));
		assertEquals(expectedMessage, result.get("errorMsg"));
		verify(datasources, never()).runQuery(any(), anyString(), anyList(), anyInt(), anyInt());
	}

	@Test
	void variablesAreBoundAsValuesNotSplicedIntoTheSql() throws Exception {
		String attack = "华南' union select account, password from sys_user --";

		Map<String, Object> result = run(Map.of(
			"sql", "select id, region from wdx074_sales where region = '${region}' limit ${n}",
			"maxRows", 50, "timeoutMs", 5000), Map.of("region", attack, "n", 5));

		assertEquals("success", result.get("status"), String.valueOf(result.get("errorMsg")));
		verify(datasources).runQuery(same(datasource),
			eq("select id, region from wdx074_sales where region = ? limit ?"),
			eq(List.of(param(attack, true), param(5L, false))), eq(50), eq(5000));
		JsonNode output = om.readTree(String.valueOf(result.get("output")));
		assertTrue(output.path("ok").asBoolean());
		assertEquals(1, output.path("rowCount").asInt());
		assertEquals("select id, region from wdx074_sales where region = ? limit ?", output.path("sql").asText());
		assertEquals("华南", output.path("rows").path(0).path("region").asText());
		assertFalse(String.valueOf(result.get("output")).contains("sys_user"));
		@SuppressWarnings("unchecked")
		Map<String, Object> vars = (Map<String, Object>) result.get("vars");
		assertEquals(ROWS, vars.get("dbRows"));
	}

	@Test
	void supportsBothPlaceholderStylesPrefixesAndLists() throws Exception {
		Map<String, Object> result = run(Map.of("sql",
			"select * from wdx074_sales where region = {{region}} and id in (${input.ids}) and product like '%${kw}%'"),
			Map.of("region", "华南", "ids", List.of(1, 2), "kw", "产品"));

		assertEquals("success", result.get("status"), String.valueOf(result.get("errorMsg")));
		verify(datasources).runQuery(same(datasource),
			eq("select * from wdx074_sales where region = ? and id in ( ?, ? ) and product like ?"),
			eq(List.of(param("华南", false), param(1L, false), param(2L, false), param("%产品%", true))),
			eq(200), eq(0));
	}

	@Test
	void nestedVariablesAndRowLimitCap() throws Exception {
		Map<String, Object> result = run(Map.of("sql", "select * from wdx074_sales where region = ${form.region}",
			"maxRows", 99999), Map.of("form", Map.of("region", "华北")));

		assertEquals("success", result.get("status"), String.valueOf(result.get("errorMsg")));
		verify(datasources).runQuery(same(datasource), eq("select * from wdx074_sales where region = ?"),
			eq(List.of(param("华北", false))), eq(2000), eq(0));
	}

	@Test
	void missingVariableStopsBeforeTheDatabase() throws Exception {
		assertRejected("数据库节点用到的变量「region」没有值",
			run(Map.of("sql", "select * from wdx074_sales where region = '${region}'"), Map.of()));
	}

	@Test
	void variablesCannotStandForTablesOrWholeStatements() throws Exception {
		assertRejected(SqlReadOnlyChecker.MSG_VAR_POSITION,
			run(Map.of("sql", "select * from ${table}"), Map.of("table", "sys_user")));
	}

	@Test
	void wholeStatementVariableIsRejected() throws Exception {
		assertRejected(SqlReadOnlyChecker.MSG_VAR_POSITION,
			run(Map.of("sql", "${sql}"), Map.of("sql", "select * from sys_user")));
	}

	@Test
	void tablesOutsideTheDatasourceWhitelistAreRejected() throws Exception {
		assertRejected("SQL 引用了未授权表: sys_user",
			run(Map.of("sql", "select s.id, u.account from wdx074_sales s, sys_user u"), Map.of()));
	}

	@Test
	void nodeWhitelistNarrowsTheDatasourceWhitelist() throws Exception {
		assertRejected("SQL 引用了未授权表: wdx074_orders",
			run(Map.of("sql", "select * from wdx074_orders", "tableWhitelist", "wdx074_sales"), Map.of()));
	}

	@Test
	void statementsThatWriteAreRejected() throws Exception {
		assertRejected(AiConstants.MSG_SQL_UNSAFE, run(Map.of("sql",
			"with x as (update wdx074_sales set amount = 0 returning id) select * from x"), Map.of()));
	}

	@Test
	void objectValuesAreRejected() throws Exception {
		assertRejected("变量「filter」不是文字或数字，不能用在查询里",
			run(Map.of("sql", "select * from wdx074_sales where region = ${filter}"),
				Map.of("filter", Map.of("region", "华南"))));
	}
}
