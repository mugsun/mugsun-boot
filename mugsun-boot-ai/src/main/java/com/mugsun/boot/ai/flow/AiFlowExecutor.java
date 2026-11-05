package com.mugsun.boot.ai.flow;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mugsun.boot.ai.entity.*;
import com.mugsun.boot.ai.mapper.AiAppNodeRunMapper;
import com.mugsun.boot.ai.mapper.AiAppRunMapper;
import com.mugsun.boot.ai.service.AiChannelBizService;
import com.mugsun.boot.ai.service.AiDatasourceBizService;
import com.mugsun.boot.ai.service.AiKnowledgeBizService;
import com.mugsun.boot.ai.service.AiMcpBizService;
import com.mugsun.boot.ai.service.AiModelBizService;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.ai.support.SqlSafetyGate;
import com.mugsun.boot.ai.support.SqlTableGate;
import com.mugsun.boot.ai.support.UrlSafetyGate;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工作流执行器：对齐手册节点
 * （start/end/switch/llm/rag/question/extract/optimize/db/http/code/text/updateVar/mcp/notice/foreach/parallel）。
 */
@Component
public class AiFlowExecutor {

	private final ObjectMapper objectMapper;
	private final AiAppRunMapper runMapper;
	private final AiAppNodeRunMapper nodeRunMapper;
	private final AiModelBizService modelBizService;
	private final AiLlmClient llmClient;
	private final AiKnowledgeBizService knowledgeBizService;
	private final AiDatasourceBizService datasourceBizService;
	private final AiMcpBizService mcpBizService;
	private final AiChannelBizService channelBizService;
	private final WebClient.Builder webClientBuilder;
	private final com.mugsun.boot.ai.support.OutboundWebClientFactory outboundWebClients;

	public AiFlowExecutor(ObjectMapper objectMapper, AiAppRunMapper runMapper,
						  AiAppNodeRunMapper nodeRunMapper, AiModelBizService modelBizService,
						  AiLlmClient llmClient, AiKnowledgeBizService knowledgeBizService,
						  AiDatasourceBizService datasourceBizService, AiMcpBizService mcpBizService,
						  AiChannelBizService channelBizService, WebClient.Builder webClientBuilder,
						  com.mugsun.boot.ai.support.OutboundWebClientFactory outboundWebClients) {
		this.outboundWebClients = outboundWebClients;
		this.objectMapper = objectMapper;
		this.runMapper = runMapper;
		this.nodeRunMapper = nodeRunMapper;
		this.modelBizService = modelBizService;
		this.llmClient = llmClient;
		this.knowledgeBizService = knowledgeBizService;
		this.datasourceBizService = datasourceBizService;
		this.mcpBizService = mcpBizService;
		this.channelBizService = channelBizService;
		this.webClientBuilder = webClientBuilder;
	}

	public Map<String, Object> run(AiApp app, Map<String, Object> input) {
		return run(app, input, null, null);
	}

	/** @see #run(AiApp, Map, Long, String) */
	public Map<String, Object> run(AiApp app, Map<String, Object> input, Long userIdOverride) {
		return run(app, input, userIdOverride, null);
	}

	/**
	 * @param userIdOverride 非空时写入 AiAppRun.userId（审批内嵌用流程发起人）；空则取当前登录人
	 * @param tenantIdOverride 非空时<strong>显式</strong>写入 AiAppRun.tenantId（审批内嵌用实例租户）；
	 *                        禁止只靠工作线程 ambient {@link TenantContext#current()}
	 */
	public Map<String, Object> run(AiApp app, Map<String, Object> input, Long userIdOverride, String tenantIdOverride) {
		long t0 = System.currentTimeMillis();
		AiAppRun run = new AiAppRun();
		run.sanitizeForInsert();
		run.setAppId(app.getId());
		if (tenantIdOverride != null && !tenantIdOverride.isBlank()) {
			run.setTenantId(tenantIdOverride.trim());
		} else {
			run.setTenantId(TenantContext.current());
		}
		if (userIdOverride != null) {
			run.setUserId(userIdOverride);
		} else {
			run.setUserId(StpUtil.getLoginIdAsLong());
		}
		run.setTraceId(IdUtil.fastSimpleUUID());
		try {
			run.setInputJson(objectMapper.writeValueAsString(input));
		} catch (Exception e) {
			run.setInputJson("{}");
		}
		run.setStatus("running");
		runMapper.insert(run);

		Map<String, Object> vars = new HashMap<>();
		if (input != null) {
			vars.putAll(input);
		}
		String output = "";
		try {
			JsonNode dsl = objectMapper.readTree(app.getDsl() == null ? "{}" : app.getDsl());
			JsonNode nodes = dsl.path("nodes");
			Map<String, JsonNode> byId = new HashMap<>();
			if (nodes.isArray()) {
				for (JsonNode n : nodes) {
					byId.put(n.path("id").asText(), n);
				}
			}
			JsonNode connections = dsl.has("connections") ? dsl.path("connections") : dsl.path("edges");
			Map<String, List<String>> next = new HashMap<>();
			if (connections.isArray()) {
				for (JsonNode c : connections) {
					String src = c.has("source") ? c.path("source").asText() : c.path("sourceNode").asText();
					String tgt = c.has("target") ? c.path("target").asText() : c.path("targetNode").asText();
					next.computeIfAbsent(src, k -> new ArrayList<>()).add(tgt);
				}
			}
			String cur = findStart(byId);
			Set<String> visited = new HashSet<>();
			int steps = 0;
			while (cur != null && visited.add(cur) && steps++ < 200) {
				JsonNode node = byId.get(cur);
				if (node == null) {
					break;
				}
				String type = nodeType(node);
				ObjectNode nodeOut = execNode(run.getId(), node, type, vars, app, byId, null, run.getTenantId());
				output = nodeOut.path("output").asText(output);
				if ("end".equalsIgnoreCase(type)) {
					break;
				}
				if ("switch".equalsIgnoreCase(type) || "question".equalsIgnoreCase(type)) {
					String branch = nodeOut.path("branch").asText("");
					cur = resolveSwitch(next.getOrDefault(cur, List.of()), byId, branch);
				} else {
					List<String> nx = next.getOrDefault(cur, List.of());
					cur = nx.isEmpty() ? null : nx.get(0);
				}
			}
			run.setStatus("success");
			run.setOutputJson(objectMapper.writeValueAsString(Map.of("output", output, "vars", vars)));
		} catch (AiFlowNodeException e) {
			run.setStatus("failed");
			run.setErrorCode(e.getErrorCode());
			run.setErrorMsg(trimMsg(e.getMessage()));
		} catch (Exception e) {
			run.setStatus("failed");
			run.setErrorCode(AiFlowControlConstants.ERR_EXECUTE);
			run.setErrorMsg(trimMsg(e.getMessage()));
		}
		run.setDurationMs(System.currentTimeMillis() - t0);
		run.sanitizeForUpdate();
		runMapper.update(run);
		Map<String, Object> resp = new HashMap<>();
		resp.put("runId", run.getId());
		resp.put("status", run.getStatus());
		resp.put("output", output);
		resp.put("vars", vars);
		resp.put("errorCode", run.getErrorCode());
		resp.put("errorMsg", run.getErrorMsg());
		return resp;
	}

	private ObjectNode execNode(Long runId, JsonNode node, String type, Map<String, Object> vars, AiApp app,
								Map<String, JsonNode> byId, String nodeIdOverride, String tenantId) throws Exception {
		long t0 = System.currentTimeMillis();
		AiAppNodeRun nr = new AiAppNodeRun();
		nr.sanitizeForInsert();
		nr.setRunId(runId);
		nr.setNodeId(nodeIdOverride != null ? nodeIdOverride : node.path("id").asText());
		nr.setNodeType(type);
		nr.setNodeName(node.path("data").path("label").asText(type));
		nr.setInputJson(objectMapper.writeValueAsString(vars));
		nr.setStatus("running");
		nodeRunMapper.insert(nr);

		ObjectNode out = objectMapper.createObjectNode();
		String output = "";
		try {
			JsonNode data = node.path("data");
			String t = type == null ? "" : type.toLowerCase(Locale.ROOT);
			switch (t) {
				case "start" -> output = String.valueOf(vars.getOrDefault("input", vars.getOrDefault("query", "")));
				case "text" -> {
					String tpl = data.path("text").asText(data.path("content").asText(""));
					output = render(tpl, vars);
					bindOut(vars, data, output);
				}
				case "llm" -> {
					AiModel model = resolveModel(data, app);
					String system = data.path("system").asText("");
					String prompt = render(data.path("prompt").asText(data.path("user").asText(
						String.valueOf(vars.getOrDefault("input", "")))), vars);
					List<Map<String, String>> msgs = new ArrayList<>();
					if (!system.isBlank()) {
						msgs.add(Map.of("role", "system", "content", system));
					}
					msgs.add(Map.of("role", "user", "content", prompt));
					int maxTokens = data.path("maxTokens").asInt(2000);
					output = llmClient.chat(model, msgs, maxTokens);
					bindOut(vars, data, output);
				}
				case "rag" -> {
					Long kbId = longOrNull(data.path("knowledgeId"));
					String q = render(data.path("query").asText(
						String.valueOf(vars.getOrDefault("input", vars.getOrDefault("query", "")))), vars);
					int topK = data.path("topK").asInt(4);
					if (kbId != null) {
						List<Map<String, Object>> hits = knowledgeBizService.hitTest(kbId, q, topK);
						output = objectMapper.writeValueAsString(hits);
						vars.put("ragRefs", hits);
						bindOut(vars, data, output);
					}
				}
				case "question" -> {
					AiModel model = resolveModel(data, app);
					String q = String.valueOf(vars.getOrDefault("input", vars.getOrDefault("query", "")));
					JsonNode cats = data.path("categories");
					StringBuilder sb = new StringBuilder("请将问题归入下列分类之一，只回复分类名称：\n");
					List<String> names = new ArrayList<>();
					if (cats.isArray()) {
						for (JsonNode c : cats) {
							String name = c.path("name").asText();
							names.add(name);
							sb.append("- ").append(name).append(": ").append(c.path("description").asText("")).append('\n');
						}
					}
					sb.append("问题：").append(q);
					String ans = llmClient.chat(model, List.of(Map.of("role", "user", "content", sb.toString())), 64);
					String branch = names.isEmpty() ? "default" : names.get(0);
					for (String n : names) {
						if (ans != null && ans.toLowerCase(Locale.ROOT).contains(n.toLowerCase(Locale.ROOT))) {
							branch = n;
							break;
						}
					}
					out.put("branch", branch);
					output = branch;
					vars.put("questionClass", branch);
				}
				case "extract" -> {
					AiModel model = resolveModel(data, app);
					String schema = data.path("fields").isMissingNode()
						? data.path("schema").asText("[]") : data.path("fields").toString();
					String src = render(data.path("input").asText(String.valueOf(vars.getOrDefault("input", ""))), vars);
					String prompt = "按字段表提取信息，仅输出 JSON：\n字段=" + schema + "\n文本=\n" + src;
					output = llmClient.chat(model, List.of(Map.of("role", "user", "content", prompt)), 1024);
					bindOut(vars, data, output);
				}
				case "optimize" -> {
					AiModel model = resolveModel(data, app);
					String optType = data.path("optimizeType").asText("polish");
					String lang = data.path("targetLang").asText("zh-CN");
					String src = render(data.path("input").asText(String.valueOf(vars.getOrDefault("input", ""))), vars);
					String prompt = switch (optType) {
						case "rewrite" -> "改写以下内容，保持原意：\n" + src;
						case "translate" -> "翻译为 " + lang + "：\n" + src;
						case "summarize" -> "摘要以下内容：\n" + src;
						default -> "润色以下内容：\n" + src;
					};
					output = llmClient.chat(model, List.of(Map.of("role", "user", "content", prompt)), 2048);
					bindOut(vars, data, output);
				}
				case "db" -> {
					String sql = data.path("sql").asText("");
					sql = render(sql.replace("${", "{{").replace("}", "}}"), vars);
					sql = SqlSafetyGate.guard(sql);
					Long dsId = longOrNull(data.path("datasourceId"));
					if (dsId == null) {
						throw new ServiceException("db 节点请配置 datasourceId");
					}
					AiDatasource ds = datasourceBizService.requireRaw(dsId);
					Set<String> allowed = SqlTableGate.parseWhitelist(ds.getTableWhitelist());
					Set<String> nodeWl = SqlTableGate.parseWhitelist(data.path("tableWhitelist").asText(""));
					if (!nodeWl.isEmpty()) {
						if (allowed.isEmpty()) {
							allowed = nodeWl;
						} else {
							allowed.retainAll(nodeWl);
							if (allowed.isEmpty()) {
								throw new ServiceException("db 节点表白名单与数据源白名单无交集");
							}
						}
					}
					SqlTableGate.assertAllowed(sql, allowed);
					List<Map<String, Object>> rows = new ArrayList<>();
					int maxRows = data.path("maxRows").asInt(200);
					try (Connection c = datasourceBizService.open(ds);
						 Statement st = c.createStatement()) {
						st.setMaxRows(Math.max(1, Math.min(maxRows, 2000)));
						try (ResultSet rs = st.executeQuery(sql)) {
							ResultSetMetaData meta = rs.getMetaData();
							int cols = meta.getColumnCount();
							while (rs.next()) {
								Map<String, Object> row = new LinkedHashMap<>();
								for (int i = 1; i <= cols; i++) {
									row.put(meta.getColumnLabel(i), rs.getObject(i));
								}
								rows.add(row);
							}
						}
					}
					ObjectNode dbOut = objectMapper.createObjectNode();
					dbOut.put("ok", true);
					dbOut.put("sql", sql);
					dbOut.put("rowCount", rows.size());
					dbOut.set("rows", objectMapper.valueToTree(rows));
					output = dbOut.toString();
					vars.put("dbRows", rows);
					bindOut(vars, data, output);
				}
				case "http" -> {
					String url = UrlSafetyGate.guard(render(data.path("url").asText(), vars));
					String method = data.path("method").asText("GET");
					WebClient client = outboundWebClients.createStrict();
					if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) || "PATCH".equalsIgnoreCase(method)) {
						String body = data.path("body").asText("{}");
						output = client.method(org.springframework.http.HttpMethod.valueOf(method.toUpperCase(Locale.ROOT)))
							.uri(url).contentType(MediaType.APPLICATION_JSON)
							.bodyValue(render(body, vars)).retrieve().bodyToMono(String.class)
							.block(Duration.ofMillis(data.path("timeout").asLong(30000)));
					} else if ("DELETE".equalsIgnoreCase(method)) {
						output = client.delete().uri(url).retrieve().bodyToMono(String.class)
							.block(Duration.ofMillis(data.path("timeout").asLong(30000)));
					} else {
						output = client.get().uri(url).retrieve().bodyToMono(String.class)
							.block(Duration.ofMillis(data.path("timeout").asLong(30000)));
					}
					if (output == null) {
						output = "";
					}
					bindOut(vars, data, output);
				}
				case "code" -> {
					boolean enabled = data.path("enabled").asBoolean(false);
					if (!enabled) {
						output = "{\"ok\":false,\"message\":\"code 节点默认关闭，需角色白名单开启\"}";
					} else {
						throw new ServiceException("code 节点沙箱未启用");
					}
					bindOut(vars, data, output);
				}
				case "updatevar", "update_var" -> {
					JsonNode ops = data.path("operations");
					if (ops.isArray()) {
						for (JsonNode op : ops) {
							String target = op.path("target").asText();
							String action = op.path("action").asText("set");
							String val = render(op.path("value").asText(""), vars);
							Object curVal = vars.get(target);
							switch (action) {
								case "append" -> vars.put(target, String.valueOf(curVal == null ? "" : curVal) + val);
								case "increment" -> vars.put(target, toLong(curVal) + toLong(val));
								case "decrement" -> vars.put(target, toLong(curVal) - toLong(val));
								case "clear" -> vars.put(target, "");
								default -> vars.put(target, val);
							}
						}
					}
					output = objectMapper.writeValueAsString(vars);
				}
				case "mcp" -> {
					Long toolId = longOrNull(data.path("mcpToolId"));
					if (toolId == null) {
						toolId = longOrNull(data.path("toolId"));
					}
					if (toolId == null) {
						throw new ServiceException("mcp 节点请配置 mcpToolId");
					}
					Map<String, Object> args = new HashMap<>(vars);
					if (data.has("arguments") && data.path("arguments").isObject()) {
						data.path("arguments").fields().forEachRemaining(e ->
							args.put(e.getKey(), e.getValue().isValueNode() ? e.getValue().asText() : e.getValue().toString()));
					}
					Map<String, Object> mcpRes = mcpBizService.invoke(toolId,
						data.path("toolName").asText("echo"), args);
					if (Boolean.FALSE.equals(mcpRes.get("ok"))) {
						throw new ServiceException(String.valueOf(mcpRes.get("error")));
					}
					output = objectMapper.writeValueAsString(mcpRes);
					bindOut(vars, data, output);
				}
				case "notice" -> {
					Long channelId = longOrNull(data.path("channelId"));
					String content = render(data.path("content").asText(
						String.valueOf(vars.getOrDefault("output", vars.getOrDefault("input", "")))), vars);
					if (channelId == null) {
						throw new ServiceException("notice 节点请配置 channelId");
					}
					Map<String, Object> sent = channelBizService.send(channelId, content, vars);
					output = objectMapper.writeValueAsString(sent);
					bindOut(vars, data, output);
				}
				case "switch" -> {
					String expr = data.path("expression").asText("");
					String left = render(expr, vars);
					String expect = data.path("value").asText("true");
					boolean pass = left.equals(expect) || "true".equalsIgnoreCase(left);
					String branch = pass ? "true" : "false";
					if (!data.path("defaultBranch").asText("").isBlank() && !pass) {
						branch = data.path("defaultBranch").asText("default");
					}
					out.put("branch", branch);
					output = branch;
				}
				case "end" -> {
					String bind = data.path("output").asText("");
					if (!bind.isBlank()) {
						output = render(bind, vars);
					} else {
						output = String.valueOf(vars.getOrDefault("output", vars.getOrDefault("input", "")));
					}
				}
				case "foreach" -> {
					execForeach(runId, node, data, vars, app, byId, tenantId, out);
					output = out.path("output").asText("");
				}
				case "parallel" -> {
					execParallel(runId, node, data, vars, app, byId, tenantId, out);
					output = out.path("output").asText("");
				}
				default -> output = "";
			}
			vars.put("output", output);
			out.put("output", output);
			nr.setStatus("success");
			nr.setOutputJson(out.toString());
		} catch (Exception e) {
			nr.setStatus("failed");
			nr.setErrorMsg(trimMsg(e.getMessage()));
			out.put("output", "");
			out.put("error", e.getMessage() == null ? "" : e.getMessage());
			if (e instanceof AiFlowNodeException afe) {
				out.put("errorCode", afe.getErrorCode());
			}
			throw e;
		} finally {
			nr.setDurationMs(System.currentTimeMillis() - t0);
			nr.sanitizeForUpdate();
			nodeRunMapper.update(nr);
		}
		return out;
	}

	private void execForeach(Long runId, JsonNode node, JsonNode data, Map<String, Object> vars, AiApp app,
							 Map<String, JsonNode> byId, String tenantId, ObjectNode out) throws Exception {
		String parentId = node.path("id").asText("foreach");
		String inputPath = data.path("inputPath").asText("");
		if (inputPath.isBlank()) {
			throw new AiFlowNodeException(AiFlowControlConstants.ERR_FOREACH_INPUT_NOT_ARRAY, "foreach 缺少 inputPath");
		}
		List<Object> items = resolveArray(vars, inputPath);
		if (items == null) {
			throw new AiFlowNodeException(AiFlowControlConstants.ERR_FOREACH_INPUT_NOT_ARRAY,
				"foreach 输入不是数组: " + inputPath);
		}
		List<String> bodyIds = readBodyNodeIds(data);
		if (bodyIds.isEmpty()) {
			throw new AiFlowNodeException(AiFlowControlConstants.ERR_FOREACH_BODY_INVALID, "foreach bodyNodeIds 不能为空");
		}
		assertBodyNoNest(bodyIds, byId, AiFlowControlConstants.ERR_FOREACH_BODY_INVALID);

		int maxIter = AiFlowControlConstants.clampMaxIterations(data.path("maxIterations").asInt(0));
		long timeoutMs = AiFlowControlConstants.clampTimeoutMs(data.path("timeoutMs").asLong(0));
		long deadline = System.currentTimeMillis() + timeoutMs;
		String itemVar = data.path("itemVar").asText("item");
		if (itemVar.isBlank()) {
			itemVar = "item";
		}
		String indexVar = data.path("indexVar").asText("index");
		if (indexVar.isBlank()) {
			indexVar = "index";
		}
		String aggregate = data.path("aggregate").asText(AiFlowControlConstants.AGG_ARRAY).toLowerCase(Locale.ROOT);
		String outputVar = data.path("outputVar").asText("foreachResults");
		if (outputVar.isBlank()) {
			outputVar = "foreachResults";
		}
		String onFail = data.path("onItemFailure").asText(AiFlowControlConstants.ON_FAIL_CONTINUE).toLowerCase(Locale.ROOT);
		boolean abort = AiFlowControlConstants.ON_FAIL_ABORT.equals(onFail);

		boolean truncated = items.size() > maxIter;
		int ran = Math.min(items.size(), maxIter);
		List<Object> collected = new ArrayList<>();
		ArrayNode iterations = objectMapper.createArrayNode();
		String lastOutput = "";

		for (int i = 0; i < ran; i++) {
			if (System.currentTimeMillis() > deadline) {
				throw new AiFlowNodeException(AiFlowControlConstants.ERR_FOREACH_TIMEOUT, "foreach 执行超时");
			}
			Object item = items.get(i);
			vars.put(itemVar, item);
			vars.put(indexVar, i);
			ObjectNode iter = objectMapper.createObjectNode();
			iter.put("index", i);
			ArrayNode steps = objectMapper.createArrayNode();
			long it0 = System.currentTimeMillis();
			try {
				String roundOut = runBodyChain(runId, bodyIds, byId, vars, app, parentId, String.valueOf(i),
					deadline, tenantId, steps, AiFlowControlConstants.ERR_FOREACH_TIMEOUT);
				lastOutput = roundOut == null ? "" : roundOut;
				iter.put("status", "success");
				iter.put("output", lastOutput);
				if (AiFlowControlConstants.AGG_ARRAY.equals(aggregate)) {
					collected.add(lastOutput);
				}
			} catch (AiFlowNodeException | ServiceException ex) {
				String code = ex instanceof AiFlowNodeException afe ? afe.getErrorCode()
					: AiFlowControlConstants.ERR_FOREACH_ITEM_ERROR;
				iter.put("status", "failed");
				iter.put("errorCode", code);
				iter.put("errorMsg", trimMsg(ex.getMessage()));
				iter.put("output", "");
				if (abort) {
					iter.put("durationMs", System.currentTimeMillis() - it0);
					iter.set("steps", steps);
					iterations.add(iter);
					out.set("iterations", iterations);
					out.put("inputCount", items.size());
					out.put("ranCount", i + 1);
					out.put("truncated", truncated);
					throw new AiFlowNodeException(AiFlowControlConstants.ERR_FOREACH_ITEM_ERROR,
						trimMsg(ex.getMessage()), ex);
				}
			} catch (Exception ex) {
				iter.put("status", "failed");
				iter.put("errorCode", AiFlowControlConstants.ERR_FOREACH_ITEM_ERROR);
				iter.put("errorMsg", trimMsg(ex.getMessage()));
				iter.put("output", "");
				if (abort) {
					iter.put("durationMs", System.currentTimeMillis() - it0);
					iter.set("steps", steps);
					iterations.add(iter);
					throw new AiFlowNodeException(AiFlowControlConstants.ERR_FOREACH_ITEM_ERROR,
						trimMsg(ex.getMessage()), ex);
				}
			}
			iter.put("durationMs", System.currentTimeMillis() - it0);
			iter.set("steps", steps);
			iterations.add(iter);
		}

		Object aggVal;
		String output;
		if (AiFlowControlConstants.AGG_NONE.equals(aggregate)) {
			aggVal = null;
			output = lastOutput;
		} else if (AiFlowControlConstants.AGG_LAST.equals(aggregate)) {
			aggVal = lastOutput;
			output = lastOutput;
			vars.put(outputVar, lastOutput);
		} else {
			aggVal = collected;
			output = objectMapper.writeValueAsString(collected);
			vars.put(outputVar, collected);
		}
		out.put("aggregate", aggregate);
		out.put("inputCount", items.size());
		out.put("ranCount", ran);
		out.put("truncated", truncated);
		out.set("iterations", iterations);
		out.put("output", output);
		if (aggVal != null && !AiFlowControlConstants.AGG_LAST.equals(aggregate)) {
			// already in vars
		}
	}

	private void execParallel(Long runId, JsonNode node, JsonNode data, Map<String, Object> vars, AiApp app,
							  Map<String, JsonNode> byId, String tenantId, ObjectNode out) throws Exception {
		String parentId = node.path("id").asText("parallel");
		JsonNode branchesNode = data.path("branches");
		if (!branchesNode.isArray()) {
			throw new AiFlowNodeException(AiFlowControlConstants.ERR_PARALLEL_BRANCH_INVALID, "parallel 缺少 branches");
		}
		int maxBranches = AiFlowControlConstants.clampMaxBranches(data.path("maxBranches").asInt(0));
		if (branchesNode.size() < 2) {
			throw new AiFlowNodeException(AiFlowControlConstants.ERR_PARALLEL_BRANCH_INVALID, "parallel 至少 2 个分支");
		}
		if (branchesNode.size() > maxBranches) {
			throw new AiFlowNodeException(AiFlowControlConstants.ERR_PARALLEL_BRANCH_INVALID,
				"parallel 分支数超过上限 " + maxBranches);
		}
		long timeoutMs = AiFlowControlConstants.clampTimeoutMs(data.path("timeoutMs").asLong(0));
		long deadline = System.currentTimeMillis() + timeoutMs;
		String tenant = tenantId == null || tenantId.isBlank() ? TenantContext.current() : tenantId;

		record BranchJob(String id, String label, List<String> bodyIds, String outputVar) {
		}
		List<BranchJob> jobs = new ArrayList<>();
		for (JsonNode b : branchesNode) {
			String id = b.path("id").asText("");
			if (id.isBlank()) {
				throw new AiFlowNodeException(AiFlowControlConstants.ERR_PARALLEL_BRANCH_INVALID, "branch.id 不能为空");
			}
			List<String> bodyIds = readBodyNodeIds(b);
			if (bodyIds.isEmpty()) {
				bodyIds = readBodyNodeIds(b.path("data"));
			}
			if (bodyIds.isEmpty()) {
				throw new AiFlowNodeException(AiFlowControlConstants.ERR_PARALLEL_BRANCH_INVALID,
					"branch bodyNodeIds 不能为空: " + id);
			}
			assertBodyNoNest(bodyIds, byId, AiFlowControlConstants.ERR_PARALLEL_BRANCH_INVALID);
			String ov = b.path("outputVar").asText("branch_" + id);
			if (ov.isBlank()) {
				ov = "branch_" + id;
			}
			jobs.add(new BranchJob(id, b.path("label").asText(id), bodyIds, ov));
		}

		ExecutorService es = Executors.newFixedThreadPool(Math.min(jobs.size(), maxBranches), r -> {
			Thread t = new Thread(r, "ai-flow-parallel");
			t.setDaemon(true);
			return t;
		});
		Map<String, Object> snapshot = new HashMap<>(vars);
		List<Future<ObjectNode>> futures = new ArrayList<>();
		try {
			for (BranchJob job : jobs) {
				futures.add(es.submit(() -> TenantContext.execute(tenant, () -> {
					ObjectNode branchOut = objectMapper.createObjectNode();
					branchOut.put("id", job.id());
					ArrayNode steps = objectMapper.createArrayNode();
					long b0 = System.currentTimeMillis();
					Map<String, Object> branchVars = new HashMap<>(snapshot);
					try {
						if (System.currentTimeMillis() > deadline) {
							throw new AiFlowNodeException(AiFlowControlConstants.ERR_PARALLEL_TIMEOUT, "parallel 分支超时");
						}
						String bout = runBodyChain(runId, job.bodyIds(), byId, branchVars, app, parentId, job.id(),
							deadline, tenant, steps, AiFlowControlConstants.ERR_PARALLEL_TIMEOUT);
						branchOut.put("status", "success");
						branchOut.put("output", bout == null ? "" : bout);
						branchOut.put("outputVar", job.outputVar());
					} catch (Exception ex) {
						String code = ex instanceof AiFlowNodeException afe ? afe.getErrorCode()
							: AiFlowControlConstants.ERR_PARALLEL_BRANCH_FAILED;
						branchOut.put("status", "failed");
						branchOut.put("errorCode", code);
						branchOut.put("errorMsg", trimMsg(ex.getMessage()));
						branchOut.put("output", "");
						branchOut.put("outputVar", job.outputVar());
					}
					branchOut.put("durationMs", System.currentTimeMillis() - b0);
					branchOut.set("steps", steps);
					return branchOut;
				})));
			}

			ArrayNode branchesOut = objectMapper.createArrayNode();
			ObjectNode parallelResults = objectMapper.createObjectNode();
			boolean partial = false;
			long remain = Math.max(1L, deadline - System.currentTimeMillis());
			for (int i = 0; i < futures.size(); i++) {
				Future<ObjectNode> f = futures.get(i);
				BranchJob job = jobs.get(i);
				ObjectNode branchOut;
				try {
					remain = Math.max(1L, deadline - System.currentTimeMillis());
					branchOut = f.get(remain, TimeUnit.MILLISECONDS);
				} catch (TimeoutException te) {
					f.cancel(true);
					partial = true;
					branchOut = objectMapper.createObjectNode();
					branchOut.put("id", job.id());
					branchOut.put("status", "timeout");
					branchOut.put("errorCode", AiFlowControlConstants.ERR_PARALLEL_TIMEOUT);
					branchOut.put("errorMsg", "parallel 分支超时");
					branchOut.put("output", "");
					branchOut.put("durationMs", timeoutMs);
					branchOut.set("steps", objectMapper.createArrayNode());
				} catch (ExecutionException ee) {
					partial = true;
					Throwable c = ee.getCause() == null ? ee : ee.getCause();
					branchOut = objectMapper.createObjectNode();
					branchOut.put("id", job.id());
					branchOut.put("status", "failed");
					branchOut.put("errorCode", AiFlowControlConstants.ERR_PARALLEL_BRANCH_FAILED);
					branchOut.put("errorMsg", trimMsg(c.getMessage()));
					branchOut.put("output", "");
					branchOut.set("steps", objectMapper.createArrayNode());
				}
				if (!"success".equals(branchOut.path("status").asText())) {
					partial = true;
				} else {
					vars.put(job.outputVar(), branchOut.path("output").asText(""));
				}
				branchesOut.add(branchOut);
				ObjectNode pr = objectMapper.createObjectNode();
				pr.put("status", branchOut.path("status").asText());
				pr.put("output", branchOut.path("output").asText(""));
				if (branchOut.hasNonNull("errorCode")) {
					pr.put("errorCode", branchOut.path("errorCode").asText());
				}
				if (branchOut.hasNonNull("errorMsg")) {
					pr.put("errorMsg", branchOut.path("errorMsg").asText());
				}
				pr.put("durationMs", branchOut.path("durationMs").asLong(0));
				parallelResults.set(job.id(), pr);
			}

			// 若整节点墙钟已过且仍有未完成，升为节点超时
			boolean anyTimeout = false;
			for (JsonNode b : branchesOut) {
				if ("timeout".equals(b.path("status").asText())) {
					anyTimeout = true;
					break;
				}
			}
			if (anyTimeout && System.currentTimeMillis() > deadline) {
				out.set("branches", branchesOut);
				out.set("parallelResults", parallelResults);
				out.put("partial", true);
				throw new AiFlowNodeException(AiFlowControlConstants.ERR_PARALLEL_TIMEOUT, "parallel 执行超时");
			}

			Map<String, Object> prMap = objectMapper.convertValue(parallelResults, Map.class);
			vars.put("parallelResults", prMap);
			String output = parallelResults.toString();
			out.set("branches", branchesOut);
			out.set("parallelResults", parallelResults);
			out.put("partial", partial);
			out.put("output", output);
		} finally {
			es.shutdownNow();
		}
	}

	private String runBodyChain(Long runId, List<String> bodyIds, Map<String, JsonNode> byId,
								Map<String, Object> vars, AiApp app, String parentId, String mid,
								long deadlineMs, String tenantId, ArrayNode stepsOut, String timeoutErr) throws Exception {
		String output = "";
		int steps = 0;
		String to = timeoutErr == null ? AiFlowControlConstants.ERR_FOREACH_TIMEOUT : timeoutErr;
		for (String id : bodyIds) {
			if (System.currentTimeMillis() > deadlineMs) {
				throw new AiFlowNodeException(to, "body 执行超时");
			}
			if (++steps > AiFlowControlConstants.FOREACH_BODY_STEPS_HARD) {
				throw new AiFlowNodeException(AiFlowControlConstants.ERR_FOREACH_BODY_INVALID,
					"单轮 body 步数超过 " + AiFlowControlConstants.FOREACH_BODY_STEPS_HARD);
			}
			JsonNode bodyNode = byId.get(id);
			if (bodyNode == null) {
				throw new AiFlowNodeException(AiFlowControlConstants.ERR_FOREACH_BODY_INVALID, "body 节点不存在: " + id);
			}
			String type = nodeType(bodyNode);
			String tl = type == null ? "" : type.toLowerCase(Locale.ROOT);
			if (AiFlowControlConstants.TYPE_FOREACH.equals(tl) || AiFlowControlConstants.TYPE_PARALLEL.equals(tl)) {
				throw new AiFlowNodeException(AiFlowControlConstants.ERR_FOREACH_BODY_INVALID,
					"P0 禁止嵌套 foreach/parallel: " + id);
			}
			String synId = AiFlowControlConstants.syntheticNodeId(parentId, mid, id);
			long s0 = System.currentTimeMillis();
			ObjectNode stepMeta = objectMapper.createObjectNode();
			stepMeta.put("nodeId", id);
			stepMeta.put("nodeType", type);
			try {
				ObjectNode bout = execNode(runId, bodyNode, type, vars, app, byId, synId, tenantId);
				output = bout.path("output").asText(output);
				stepMeta.put("status", "success");
				stepMeta.putNull("errorMsg");
			} catch (Exception ex) {
				stepMeta.put("status", "failed");
				stepMeta.put("errorMsg", trimMsg(ex.getMessage()));
				stepMeta.put("durationMs", System.currentTimeMillis() - s0);
				stepsOut.add(stepMeta);
				throw ex;
			}
			stepMeta.put("durationMs", System.currentTimeMillis() - s0);
			stepsOut.add(stepMeta);
		}
		return output;
	}

	private void assertBodyNoNest(List<String> bodyIds, Map<String, JsonNode> byId, String errCode) {
		for (String id : bodyIds) {
			JsonNode n = byId.get(id);
			if (n == null) {
				throw new AiFlowNodeException(errCode, "body 节点不存在: " + id);
			}
			String tl = nodeType(n).toLowerCase(Locale.ROOT);
			if (AiFlowControlConstants.TYPE_FOREACH.equals(tl) || AiFlowControlConstants.TYPE_PARALLEL.equals(tl)) {
				throw new AiFlowNodeException(errCode, "P0 禁止嵌套 foreach/parallel: " + id);
			}
		}
	}

	private List<String> readBodyNodeIds(JsonNode data) {
		List<String> ids = new ArrayList<>();
		if (data == null || data.isMissingNode() || data.isNull()) {
			return ids;
		}
		JsonNode arr = data.path("bodyNodeIds");
		if (arr.isArray()) {
			for (JsonNode n : arr) {
				String id = n.asText("").trim();
				if (!id.isEmpty()) {
					ids.add(id);
				}
			}
		}
		return ids;
	}

	@SuppressWarnings("unchecked")
	private List<Object> resolveArray(Map<String, Object> vars, String inputPath) {
		Object raw = resolvePath(vars, inputPath);
		if (raw == null) {
			return null;
		}
		if (raw instanceof List<?> list) {
			return new ArrayList<>(list);
		}
		if (raw instanceof Object[] arr) {
			return new ArrayList<>(Arrays.asList(arr));
		}
		if (raw instanceof JsonNode jn) {
			if (jn.isArray()) {
				List<Object> out = new ArrayList<>();
				jn.forEach(x -> out.add(x.isValueNode() ? (x.isNumber() ? x.numberValue() : x.asText()) : x));
				return out;
			}
			return null;
		}
		if (raw instanceof String s) {
			String t = s.trim();
			if (t.startsWith("[")) {
				try {
					JsonNode n = objectMapper.readTree(t);
					if (n.isArray()) {
						List<Object> out = new ArrayList<>();
						n.forEach(x -> out.add(x.isValueNode() ? (x.isNumber() ? x.numberValue() : x.asText()) : x.toString()));
						return out;
					}
				} catch (Exception ignored) {
					return null;
				}
			}
			return null;
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private Object resolvePath(Map<String, Object> vars, String path) {
		if (path == null || path.isBlank()) {
			return null;
		}
		String[] parts = path.split("\\.");
		Object cur = vars;
		for (String p : parts) {
			if (cur == null) {
				return null;
			}
			if (cur instanceof Map<?, ?> m) {
				cur = m.get(p);
			} else if (cur instanceof JsonNode jn) {
				cur = jn.get(p);
				if (cur != null && ((JsonNode) cur).isMissingNode()) {
					return null;
				}
			} else {
				return null;
			}
		}
		return cur;
	}

	private AiModel resolveModel(JsonNode data, AiApp app) {
		Long mid = longOrNull(data.path("modelId"));
		if (mid != null) {
			return modelBizService.requireRaw(mid);
		}
		if (app.getModelId() != null) {
			return modelBizService.requireRaw(app.getModelId());
		}
		return modelBizService.requireDefaultChat();
	}

	/** 兼容雪花 id 以 JSON 字符串下发（避免 JS Number 精度丢失） */
	private static Long longOrNull(JsonNode n) {
		if (n == null || n.isNull() || n.isMissingNode()) {
			return null;
		}
		if (n.isIntegralNumber()) {
			return n.asLong();
		}
		if (n.isTextual()) {
			String t = n.asText("").trim();
			if (t.isEmpty() || "null".equalsIgnoreCase(t)) {
				return null;
			}
			return Long.valueOf(t);
		}
		return null;
	}

	private void bindOut(Map<String, Object> vars, JsonNode data, String output) {
		String outVar = data.path("outputVar").asText(data.path("outputVariable").asText(""));
		if (!outVar.isBlank()) {
			vars.put(outVar, output);
		}
	}

	private long toLong(Object v) {
		try {
			return Long.parseLong(String.valueOf(v == null ? "0" : v));
		} catch (Exception e) {
			return 0L;
		}
	}

	private String nodeType(JsonNode node) {
		String t = node.path("type").asText("");
		if (t.isBlank() || "default".equals(t) || "custom".equals(t)) {
			t = node.path("data").path("type").asText("");
		}
		return t;
	}

	private String findStart(Map<String, JsonNode> byId) {
		for (JsonNode n : byId.values()) {
			if ("start".equalsIgnoreCase(nodeType(n))) {
				return n.path("id").asText();
			}
		}
		return byId.isEmpty() ? null : byId.keySet().iterator().next();
	}

	private String resolveSwitch(List<String> targets, Map<String, JsonNode> byId, String branch) {
		for (String t : targets) {
			JsonNode n = byId.get(t);
			if (n != null) {
				String c = n.path("data").path("case").asText(n.path("data").path("branch").asText(""));
				if (branch.equalsIgnoreCase(c)) {
					return t;
				}
			}
		}
		return targets.isEmpty() ? null : targets.get(0);
	}

	private String render(String tpl, Map<String, Object> vars) {
		if (tpl == null) {
			return "";
		}
		String s = tpl;
		for (Map.Entry<String, Object> e : vars.entrySet()) {
			s = s.replace("{{" + e.getKey() + "}}", String.valueOf(e.getValue()));
			s = s.replace("{{input." + e.getKey() + "}}", String.valueOf(e.getValue()));
			s = s.replace("{{global." + e.getKey() + "}}", String.valueOf(e.getValue()));
		}
		return s;
	}

	private static String trimMsg(String m) {
		if (m == null) {
			return "";
		}
		String t = m.trim();
		return t.length() > 200 ? t.substring(0, 200) : t;
	}
}
