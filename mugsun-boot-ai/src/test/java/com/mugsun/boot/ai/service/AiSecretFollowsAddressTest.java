package com.mugsun.boot.ai.service;

import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiDatasource;
import com.mugsun.boot.ai.entity.AiMcpTool;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.boot.ai.entity.AiVectorStore;
import com.mugsun.boot.ai.mapper.AiDatasourceMapper;
import com.mugsun.boot.ai.mapper.AiMcpToolMapper;
import com.mugsun.boot.ai.mapper.AiModelMapper;
import com.mugsun.boot.ai.mapper.AiVectorStoreMapper;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.ai.support.AiOutboundProperties;
import com.mugsun.boot.ai.support.OutboundWebClientFactory;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编辑时改了地址而密钥留空：保存被拒绝，不探活、不写库，库里的密钥不会跟到新地址。
 * 地址没变留空照旧沿用；地址变了同时填了新密钥正常保存。
 */
class AiSecretFollowsAddressTest {

	private static final String MODEL_OLD = "http://localhost:11434/v1";
	private static final String MODEL_NEW = "http://localhost:11435/v1";
	private static final String MCP_OLD = "http://localhost:8931/sse";
	private static final String MCP_NEW = "http://localhost:8932/sse";
	private static final String JDBC_OLD = "jdbc:postgresql://db-old.example.com:5432/app";
	private static final String JDBC_NEW = "jdbc:postgresql://db-new.example.com:5432/app";

	private final AiModelMapper modelMapper = Mockito.mock(AiModelMapper.class);
	private final AiLlmClient llm = Mockito.mock(AiLlmClient.class);
	private final AiMcpToolMapper mcpMapper = Mockito.mock(AiMcpToolMapper.class);
	private final AiDatasourceMapper dsMapper = Mockito.mock(AiDatasourceMapper.class);
	private final AiVectorStoreMapper vectorMapper = Mockito.mock(AiVectorStoreMapper.class);

	private static AiOutboundProperties outbound() {
		AiOutboundProperties props = new AiOutboundProperties();
		props.setAllowHosts(List.of("localhost"));
		return props;
	}

	private static void assertRejected(String expected, Runnable save) {
		ServiceException e = assertThrows(ServiceException.class, save::run);
		assertTrue(e.getMessage() != null && e.getMessage().contains(expected), e.getMessage());
	}

	// ---- 模型

	private AiModelBizService modelService() {
		return new AiModelBizService(Mockito.mock(AiModuleService.class), modelMapper, llm, outbound());
	}

	private static AiModel model(String baseUrl, String apiKey, String secretKey) {
		AiModel m = new AiModel();
		m.setId(1L);
		m.setModelName("测试模型");
		m.setModelType("chat");
		m.setProvider("ollama");
		m.setModelCode("qwen2");
		m.setBaseUrl(baseUrl);
		m.setApiKey(apiKey);
		m.setSecretKey(secretKey);
		return m;
	}

	/** 探活那一刻请求会带着什么去哪里：地址|API Key|Secret Key */
	private List<String> recordProbes() {
		List<String> seen = new ArrayList<>();
		Mockito.when(llm.probe(Mockito.any())).thenAnswer(inv -> {
			AiModel m = inv.getArgument(0);
			seen.add(m.getBaseUrl() + "|" + m.getApiKey() + "|" + m.getSecretKey());
			return true;
		});
		return seen;
	}

	@Test
	void modelNewAddressWithoutKeyIsRejectedBeforeProbe() {
		Mockito.when(modelMapper.selectOneById(1L)).thenReturn(model(MODEL_OLD, "stored-key", null));
		List<String> probes = recordProbes();

		assertRejected("接口地址改了，请同时重新填写 API Key。", () -> modelService().submit(model(MODEL_NEW, "", null)));
		assertRejected("接口地址改了，请同时重新填写 API Key。", () -> modelService().submit(model(MODEL_NEW, null, null)));
		assertRejected("接口地址改了，请同时重新填写 API Key。", () -> modelService().submit(model(MODEL_NEW, "   ", null)));

		assertEquals(List.of(), probes, "被拒绝的保存不应该发出探活请求");
		Mockito.verify(modelMapper, Mockito.never()).update(Mockito.any(AiModel.class));
	}

	@Test
	void modelEverySavedKeyHasToBeEnteredAgain() {
		Mockito.when(modelMapper.selectOneById(1L)).thenReturn(model(MODEL_OLD, "stored-key", "stored-secret"));
		List<String> probes = recordProbes();

		assertRejected("重新填写 API Key 和 Secret Key。", () -> modelService().submit(model(MODEL_NEW, "", "")));
		assertRejected("重新填写 Secret Key。", () -> modelService().submit(model(MODEL_NEW, "new-key", "")));
		assertRejected("重新填写 API Key。", () -> modelService().submit(model(MODEL_NEW, "", "new-secret")));
		assertEquals(List.of(), probes);

		modelService().submit(model(MODEL_NEW, "new-key", "new-secret"));
		assertEquals(List.of(MODEL_NEW + "|new-key|new-secret"), probes);
		Mockito.verify(modelMapper).update(Mockito.any(AiModel.class));
	}

	@Test
	void modelSameAddressStillKeepsTheSavedKey() {
		Mockito.when(modelMapper.selectOneById(1L)).thenReturn(model(MODEL_OLD, "stored-key", null));
		List<String> probes = recordProbes();

		modelService().submit(model("  " + MODEL_OLD + "  ", "", null));

		assertEquals(List.of(MODEL_OLD + "|stored-key|null"), probes);
		Mockito.verify(modelMapper).update(Mockito.any(AiModel.class));
	}

	@Test
	void modelWithoutSavedKeyCanChangeAddress() {
		Mockito.when(modelMapper.selectOneById(1L)).thenReturn(model(MODEL_OLD, null, null));
		List<String> probes = recordProbes();

		modelService().submit(model(MODEL_NEW, "", null));

		assertEquals(List.of(MODEL_NEW + "|null|null"), probes);
		Mockito.verify(modelMapper).update(Mockito.any(AiModel.class));
	}

	// ---- MCP 工具

	private AiMcpBizService mcpService() {
		return new AiMcpBizService(Mockito.mock(AiModuleService.class), mcpMapper, WebClient.builder(), outbound(),
			Mockito.mock(OutboundWebClientFactory.class));
	}

	private static AiMcpTool mcp(String transport, String url, String apiKey) {
		AiMcpTool t = new AiMcpTool();
		t.setId(2L);
		t.setName("测试工具");
		t.setDescription("单元测试用");
		t.setCategory("custom");
		t.setTransport(transport);
		if ("stdio".equals(transport)) {
			t.setCommand("echo ok");
		} else {
			t.setSseUrl(url);
		}
		t.setApiKey(apiKey);
		return t;
	}

	/** 写库那一刻的地址和密钥：地址|密钥（返回前密钥会被抹掉，所以在写库时记） */
	private List<String> recordMcpUpdates() {
		List<String> seen = new ArrayList<>();
		Mockito.when(mcpMapper.update(Mockito.any(AiMcpTool.class))).thenAnswer(inv -> {
			AiMcpTool t = inv.getArgument(0);
			seen.add(t.getSseUrl() + "|" + t.getApiKey());
			return 1;
		});
		return seen;
	}

	@Test
	void mcpNewAddressWithoutKeyIsRejected() {
		Mockito.when(mcpMapper.selectOneById(2L)).thenReturn(mcp("sse", MCP_OLD, "stored-key"));
		List<String> updates = recordMcpUpdates();

		assertRejected("地址改了，请同时重新填写密钥。", () -> mcpService().submit(mcp("sse", MCP_NEW, "")));
		assertRejected("地址改了，请同时重新填写密钥。", () -> mcpService().submit(mcp("http", MCP_NEW, null)));

		assertEquals(List.of(), updates);
	}

	@Test
	void mcpSavedKeyDoesNotSurviveADetourThroughStdio() {
		// 先改成 STDIO（地址清空、密钥还在库里），再改回远程并填新地址：一样要重填
		Mockito.when(mcpMapper.selectOneById(2L)).thenReturn(mcp("stdio", null, "stored-key"));
		List<String> updates = recordMcpUpdates();

		assertRejected("地址改了，请同时重新填写密钥。", () -> mcpService().submit(mcp("sse", MCP_NEW, "")));

		assertEquals(List.of(), updates);
	}

	@Test
	void mcpSameAddressKeepsKeyAndNewAddressTakesNewKey() {
		Mockito.when(mcpMapper.selectOneById(2L)).thenAnswer(inv -> mcp("sse", MCP_OLD, "stored-key"));
		List<String> updates = recordMcpUpdates();

		mcpService().submit(mcp("sse", MCP_OLD, ""));
		mcpService().submit(mcp("sse", MCP_NEW, "new-key"));

		assertEquals(List.of(MCP_OLD + "|stored-key", MCP_NEW + "|new-key"), updates);
	}

	// ---- AI 数据源

	private AiDatasourceBizService dsService() {
		return new AiDatasourceBizService(Mockito.mock(AiModuleService.class), dsMapper);
	}

	private static AiDatasource datasource(String url, String password) {
		AiDatasource d = new AiDatasource();
		d.setId(3L);
		d.setName("测试数据源");
		d.setDbType("postgresql");
		d.setJdbcUrl(url);
		d.setUsername("reader");
		d.setPassword(password);
		return d;
	}

	private List<String> recordDatasourceUpdates() {
		List<String> seen = new ArrayList<>();
		Mockito.when(dsMapper.update(Mockito.any(AiDatasource.class))).thenAnswer(inv -> {
			AiDatasource d = inv.getArgument(0);
			seen.add(d.getJdbcUrl() + "|" + d.getPassword());
			return 1;
		});
		return seen;
	}

	@Test
	void datasourceNewAddressWithoutPasswordIsRejected() {
		Mockito.when(dsMapper.selectOneById(3L)).thenReturn(datasource(JDBC_OLD, "stored-pass"));
		List<String> updates = recordDatasourceUpdates();

		assertRejected("JDBC 地址改了，请同时重新填写密码。", () -> dsService().submit(datasource(JDBC_NEW, "")));
		assertRejected("JDBC 地址改了，请同时重新填写密码。", () -> dsService().submit(datasource(JDBC_NEW, null)));
		// 只加连接参数也算改了地址：参数能改变密码怎么发出去
		assertRejected("JDBC 地址改了，请同时重新填写密码。",
			() -> dsService().submit(datasource(JDBC_OLD + "?sslmode=disable", "")));

		assertEquals(List.of(), updates);
	}

	@Test
	void datasourceSameAddressKeepsPasswordAndNewAddressTakesNewPassword() {
		Mockito.when(dsMapper.selectOneById(3L)).thenAnswer(inv -> datasource(JDBC_OLD, "stored-pass"));
		List<String> updates = recordDatasourceUpdates();

		dsService().submit(datasource(JDBC_OLD, ""));
		dsService().submit(datasource(JDBC_NEW, "new-pass"));

		assertEquals(List.of(JDBC_OLD + "|stored-pass", JDBC_NEW + "|new-pass"), updates);
	}

	// ---- 向量库

	private AiVectorStoreBizService vectorService() {
		return new AiVectorStoreBizService(Mockito.mock(AiModuleService.class), vectorMapper);
	}

	private static AiVectorStore vector(String url, String password) {
		AiVectorStore v = new AiVectorStore();
		v.setId(4L);
		v.setName("测试向量库");
		v.setStoreType("pgvector");
		v.setUrl(url);
		v.setPassword(password);
		return v;
	}

	private List<String> recordVectorUpdates() {
		List<String> seen = new ArrayList<>();
		Mockito.when(vectorMapper.update(Mockito.any(AiVectorStore.class))).thenAnswer(inv -> {
			AiVectorStore v = inv.getArgument(0);
			seen.add(v.getHost() + ":" + v.getPort() + "/" + v.getDatabaseName() + "|" + v.getPassword());
			return 1;
		});
		return seen;
	}

	@Test
	void vectorNewServerWithoutPasswordIsRejected() {
		Mockito.when(vectorMapper.selectOneById(4L))
			.thenReturn(vector("jdbc:postgresql://vec-old.example.com:5432/vec", "stored-pass"));
		List<String> updates = recordVectorUpdates();

		assertRejected("地址改了，请同时重新填写密码。",
			() -> vectorService().submit(vector("jdbc:postgresql://vec-new.example.com:5432/vec", "")));
		assertRejected("地址改了，请同时重新填写密码。",
			() -> vectorService().submit(vector("jdbc:postgresql://vec-old.example.com:6543/vec", null)));

		assertEquals(List.of(), updates);
	}

	@Test
	void vectorSameServerKeepsPasswordAndNewServerTakesNewPassword() {
		Mockito.when(vectorMapper.selectOneById(4L))
			.thenAnswer(inv -> vector("jdbc:postgresql://vec-old.example.com:5432/vec", "stored-pass"));
		List<String> updates = recordVectorUpdates();

		// 换库名连的还是同一台服务器，不用重填
		vectorService().submit(vector("jdbc:postgresql://vec-old.example.com:5432/other", ""));
		vectorService().submit(vector("jdbc:postgresql://vec-new.example.com:5432/vec", "new-pass"));

		assertEquals(List.of("vec-old.example.com:5432/other|stored-pass", "vec-new.example.com:5432/vec|new-pass"),
			updates);
	}
}
