package com.mugsun.boot.ai.flow;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * 工作流执行器：对齐手册 15 节点（start/end/switch/llm/rag/question/extract/optimize/db/http/code/text/updateVar/mcp/notice）。
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

	public AiFlowExecutor(ObjectMapper objectMapper, AiAppRunMapper runMapper,
						  AiAppNodeRunMapper nodeRunMapper, AiModelBizService modelBizService,
						  AiLlmClient llmClient, AiKnowledgeBizService knowledgeBizService,
						  AiDatasourceBizService datasourceBizService, AiMcpBizService mcpBizService,
						  AiChannelBizService channelBizService, WebClient.Builder webClientBuilder) {
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
		long t0 = System.currentTimeMillis();
		AiAppRun run = new AiAppRun();
		run.sanitizeForInsert();
		run.setAppId(app.getId());
		run.setTenantId(TenantContext.current());
		run.setUserId(StpUtil.getLoginIdAsLong());
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
				ObjectNode nodeOut = execNode(run.getId(), node, type, vars, app);
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
		} catch (Exception e) {
			run.setStatus("failed");
			run.setErrorCode("EXECUTE_ERROR");
			run.setErrorMsg(e.getMessage());
		}
		run.setDurationMs(System.currentTimeMillis() - t0);
		run.sanitizeForUpdate();
		runMapper.update(run);
		Map<String, Object> resp = new HashMap<>();
		resp.put("runId", run.getId());
		resp.put("status", run.getStatus());
		resp.put("output", output);
		resp.put("errorMsg", run.getErrorMsg());
		return resp;
	}

	private ObjectNode execNode(Long runId, JsonNode node, String type, Map<String, Object> vars, AiApp app) throws Exception {
		long t0 = System.currentTimeMillis();
		AiAppNodeRun nr = new AiAppNodeRun();
		nr.sanitizeForInsert();
		nr.setRunId(runId);
		nr.setNodeId(node.path("id").asText());
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
					// 数据源表白名单；节点 data.tableWhitelist 可再收窄
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
					WebClient client = webClientBuilder.build();
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
					// GraalJS 默认关闭：返回入参镜像，避免任意代码执行
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
						throw new ServiceException("MCP 调用失败: " + String.valueOf(mcpRes.get("error")));
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
				default -> output = "";
			}
			vars.put("output", output);
			out.put("output", output);
			nr.setStatus("success");
			nr.setOutputJson(out.toString());
		} catch (Exception e) {
			nr.setStatus("failed");
			nr.setErrorMsg(e.getMessage());
			out.put("output", "");
			out.put("error", e.getMessage() == null ? "" : e.getMessage());
			throw e;
		} finally {
			nr.setDurationMs(System.currentTimeMillis() - t0);
			nr.sanitizeForUpdate();
			nodeRunMapper.update(nr);
		}
		return out;
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
}
