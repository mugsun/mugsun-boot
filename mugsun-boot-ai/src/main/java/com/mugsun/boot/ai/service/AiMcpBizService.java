package com.mugsun.boot.ai.service;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiMcpTool;
import com.mugsun.boot.ai.mapper.AiMcpToolMapper;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class AiMcpBizService {

	private final AiModuleService moduleService;
	private final AiMcpToolMapper mapper;
	private final WebClient.Builder webClientBuilder;

	public AiMcpBizService(AiModuleService moduleService, AiMcpToolMapper mapper,
						   WebClient.Builder webClientBuilder) {
		this.moduleService = moduleService;
		this.mapper = mapper;
		this.webClientBuilder = webClientBuilder;
	}

	public Page<AiMcpTool> page(long pageNum, long pageSize, String name) {
		moduleService.requireEnabled();
		QueryWrapper q = QueryWrapper.create().orderBy("id", false);
		if (name != null && !name.isBlank()) {
			q.like("name", name.trim());
		}
		Page<AiMcpTool> page = mapper.paginate(pageNum, pageSize, q);
		page.getRecords().forEach(this::mask);
		return page;
	}

	public AiMcpTool submit(AiMcpTool body) {
		moduleService.requireEnabled();
		if (body.getName() == null || body.getName().isBlank()) {
			throw new ServiceException("请填写名称");
		}
		if (body.getId() == null) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			if (body.getStatus() == null) {
				body.setStatus(AiConstants.STATUS_ENABLE);
			}
			if (body.getLockFlag() == null) {
				body.setLockFlag(AiConstants.FLAG_NO);
			}
			if (body.getDefaultFlag() == null) {
				body.setDefaultFlag(AiConstants.FLAG_NO);
			}
			if (body.getTransport() == null || body.getTransport().isBlank()) {
				body.setTransport("local");
			}
			if (body.getToolsJson() == null || body.getToolsJson().isBlank()) {
				body.setToolsJson("[{\"name\":\"echo\",\"description\":\"echo input\"}]");
			}
			mapper.insert(body);
		} else {
			AiMcpTool db = require(body.getId());
			String keep = db.getApiKey();
			body.sanitizeForUpdate();
			if (body.getApiKey() == null || body.getApiKey().isBlank()) {
				body.setApiKey(keep);
			}
			mapper.update(body);
		}
		mask(body);
		return body;
	}

	public void remove(List<Long> ids) {
		moduleService.requireEnabled();
		for (Long id : ids) {
			require(id);
			mapper.deleteById(id);
		}
	}

	public Map<String, Object> parse(Long id) {
		moduleService.requireEnabled();
		AiMcpTool tool = require(id);
		String transport = tool.getTransport() == null ? "local" : tool.getTransport().trim().toLowerCase();
		boolean remote = "http".equals(transport) || "sse".equals(transport) || "streamable".equals(transport);
		if (remote) {
			if (tool.getSseUrl() == null || tool.getSseUrl().isBlank()) {
				throw new ServiceException("HTTP/SSE MCP 未配置 sseUrl，无法解析工具列表");
			}
			try {
				WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().get().uri(tool.getSseUrl().trim());
				if (tool.getApiKey() != null && !tool.getApiKey().isBlank()) {
					req = req.header("Authorization", "Bearer " + tool.getApiKey());
				}
				String body = req.retrieve().bodyToMono(String.class).block(Duration.ofSeconds(10));
				if (body == null || body.isBlank()) {
					throw new ServiceException("MCP 返回空响应");
				}
				tool.setToolsJson(body);
				tool.sanitizeForUpdate();
				mapper.update(tool);
				mask(tool);
				return Map.of("ok", true, "toolsJson", body, "transport", transport);
			} catch (ServiceException e) {
				throw e;
			} catch (Exception e) {
				throw new ServiceException("MCP 解析失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
			}
		}
		String toolsJson = tool.getToolsJson() == null || tool.getToolsJson().isBlank()
			? "[{\"name\":\"echo\",\"description\":\"local echo\"}]" : tool.getToolsJson();
		tool.setToolsJson(toolsJson);
		tool.sanitizeForUpdate();
		mapper.update(tool);
		mask(tool);
		return Map.of("ok", true, "toolsJson", toolsJson, "transport", "local");
	}

	public Map<String, Object> debug(Long id, Map<String, Object> args) {
		return invoke(id, "echo", args);
	}

	/**
	 * 调用 MCP 工具。
	 * - transport=local：本地 echo（编排自测）
	 * - http/sse：必须有 sseUrl，真实 HTTP POST；失败返回 ok=false，不伪装成功
	 */
	public Map<String, Object> invoke(Long id, String toolName, Map<String, Object> args) {
		moduleService.requireEnabled();
		AiMcpTool tool = require(id);
		Map<String, Object> payload = args == null ? Map.of() : args;
		String name = toolName == null || toolName.isBlank() ? "echo" : toolName;
		String transport = tool.getTransport() == null ? "local" : tool.getTransport().trim().toLowerCase();
		boolean remote = "http".equals(transport) || "sse".equals(transport) || "streamable".equals(transport)
			|| (tool.getSseUrl() != null && !tool.getSseUrl().isBlank());

		if (remote) {
			if (tool.getSseUrl() == null || tool.getSseUrl().isBlank()) {
				return Map.of("ok", false, "tool", name, "transport", transport,
					"error", "未配置 sseUrl，拒绝伪成功");
			}
			try {
				String url = resolveCallUrl(tool.getSseUrl().trim());
				var spec = webClientBuilder.build().post().uri(url)
					.contentType(org.springframework.http.MediaType.APPLICATION_JSON);
				if (tool.getApiKey() != null && !tool.getApiKey().isBlank()) {
					spec = spec.header("Authorization", "Bearer " + tool.getApiKey());
				}
				String body = spec.bodyValue(Map.of("name", name, "arguments", payload))
					.retrieve().bodyToMono(String.class).block(Duration.ofSeconds(30));
				return Map.of("ok", true, "tool", name, "transport", "http",
					"result", body == null ? "" : body);
			} catch (Exception e) {
				return Map.of("ok", false, "tool", name, "transport", "http",
					"error", e.getMessage() == null ? "mcp call failed" : e.getMessage());
			}
		}
		return Map.of("ok", true, "tool", name, "transport", "local", "echo", payload);
	}

	/** SSE 入口常见 /sse → 调用改 /call；已含 /call 则原样 */
	public static String resolveCallUrl(String sseUrl) {
		String url = sseUrl.trim();
		if (url.contains("/call")) {
			return url;
		}
		if (url.endsWith("/sse")) {
			return url.substring(0, url.length() - 4) + "/call";
		}
		if (url.endsWith("sse") && !url.endsWith("sseUrl")) {
			return url.replaceAll("sse$", "call");
		}
		if (url.endsWith("/")) {
			return url + "call";
		}
		return url + "/call";
	}

	private AiMcpTool require(Long id) {
		AiMcpTool t = mapper.selectOneById(id);
		if (t == null) {
			throw new ServiceException(AiConstants.MSG_MCP_MISSING);
		}
		return t;
	}

	private void mask(AiMcpTool t) {
		if (t != null) {
			t.setApiKey(null);
		}
	}
}
