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

	public Page<AiMcpTool> page(long pageNum, long pageSize, String name, String category) {
		moduleService.requireEnabled();
		if (pageNum < 1) {
			throw new ServiceException("页码请从 1 开始");
		}
		if (pageSize < 1 || pageSize > 100) {
			throw new ServiceException("每页条数请在 1 到 100 之间");
		}
		QueryWrapper q = QueryWrapper.create().orderBy("id", false);
		if (name != null && !name.isBlank()) {
			String keyword = name.trim();
			if (keyword.length() > 64) {
				keyword = keyword.substring(0, 64);
			}
			q.like("name", keyword);
		}
		if (category != null && !category.isBlank()) {
			q.eq("category", category.trim());
		}
		Page<AiMcpTool> page = mapper.paginate(pageNum, pageSize, q);
		page.getRecords().forEach(this::mask);
		return page;
	}

	public AiMcpTool submit(AiMcpTool body) {
		moduleService.requireEnabled();
		if (body == null) {
			throw new ServiceException("请填写协议名称。用来在列表里区分");
		}
		String name = body.getName() == null ? "" : body.getName().trim();
		if (name.isEmpty()) {
			throw new ServiceException("请填写协议名称。用来在列表里区分");
		}
		if (name.length() > 64) {
			throw new ServiceException("协议名称请控制在 64 个字以内");
		}
		String description = body.getDescription() == null ? "" : body.getDescription().trim();
		if (description.isEmpty()) {
			throw new ServiceException("请填写协议描述。用来说明这个工具做什么");
		}
		if (description.length() > 255) {
			throw new ServiceException("协议描述请控制在 255 个字以内");
		}
		String category = body.getCategory() == null ? "" : body.getCategory().trim();
		if (category.isEmpty()) {
			category = "custom";
		}
		if (!category.matches("map|chat|browser|file|custom")) {
			throw new ServiceException("分类请选择地图、聊天、浏览器、文件或自定义");
		}
		String transport = body.getTransport() == null ? "" : body.getTransport().trim().toLowerCase();
		if (transport.isEmpty()) {
			transport = "local";
		}
		if (!transport.matches("local|sse|http|streamable|stdio")) {
			throw new ServiceException("协议请选择 SSE、HTTP 或 STDIO");
		}
		String sseUrl = body.getSseUrl() == null ? "" : body.getSseUrl().trim();
		if (isRemote(transport)) {
			if (sseUrl.isEmpty()) {
				throw new ServiceException("请填写地址。需要以 http:// 或 https:// 开头");
			}
			if (sseUrl.length() > 512 || sseUrl.contains(" ") || !sseUrl.matches("https?://[^\\s<>\"']{1,500}")) {
				throw new ServiceException("地址需要以 http:// 或 https:// 开头，并且不要带空格");
			}
		}
		String command = body.getCommand() == null ? "" : body.getCommand().trim();
		if ("stdio".equals(transport)) {
			if (command.isEmpty()) {
				throw new ServiceException("请填写命令。STDIO 需要知道要启动什么");
			}
			if (command.length() > 2000) {
				throw new ServiceException("命令请控制在 2000 个字符以内");
			}
		}
		if (body.getStatus() != null && body.getStatus() != 0 && body.getStatus() != 1) {
			throw new ServiceException("状态请选择启用或停用");
		}
		String apiKey = body.getApiKey() == null ? "" : body.getApiKey().trim();
		if (apiKey.length() > 128) {
			throw new ServiceException("密钥请控制在 128 个字以内。留空表示沿用原来的");
		}
		String envJson = body.getEnvJson() == null ? "" : body.getEnvJson().trim();
		if (!envJson.isEmpty()) {
			if (envJson.length() > 4000 || !(envJson.startsWith("{") && envJson.endsWith("}"))) {
				throw new ServiceException("环境变量需要写成 JSON 对象。留空表示不额外传");
			}
		}
		requireUnique(name, body.getId());
		if (body.getId() == null) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			body.setName(name);
			body.setDescription(description);
			body.setCategory(category);
			body.setTransport(transport);
			body.setSseUrl(sseUrl.isEmpty() ? null : sseUrl);
			body.setCommand(command.isEmpty() ? null : command);
			body.setEnvJson(envJson.isEmpty() ? null : envJson);
			body.setApiKey(apiKey.isEmpty() ? null : apiKey);
			body.setStatus(body.getStatus() == null ? AiConstants.STATUS_ENABLE : body.getStatus());
			body.setLockFlag(AiConstants.FLAG_NO);
			body.setDefaultFlag(AiConstants.FLAG_NO);
			if (!isRemote(transport) && (body.getToolsJson() == null || body.getToolsJson().isBlank())) {
				body.setToolsJson("[{\"name\":\"echo\",\"description\":\"echo input\"}]");
			}
			mapper.insert(body);
			mask(body);
			return body;
		}
		AiMcpTool db = require(body.getId());
		if (db.getLockFlag() != null && db.getLockFlag() == AiConstants.FLAG_YES) {
			throw new ServiceException("这个工具已锁定。请先解锁再改");
		}
		db.setName(name);
		db.setDescription(description);
		db.setCategory(category);
		db.setTransport(transport);
		db.setSseUrl(sseUrl.isEmpty() ? null : sseUrl);
		db.setCommand(command.isEmpty() ? null : command);
		db.setEnvJson(envJson.isEmpty() ? null : envJson);
		if (!apiKey.isEmpty()) {
			db.setApiKey(apiKey);
		}
		if (body.getStatus() != null) {
			db.setStatus(body.getStatus());
		}
		mapper.update(db);
		mask(db);
		return db;
	}

	public List<AiMcpTool> remove(List<Long> ids) {
		moduleService.requireEnabled();
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的 MCP");
		}
		List<AiMcpTool> rows = new java.util.ArrayList<>();
		for (Long id : ids) {
			AiMcpTool row = require(id);
			if (row.getLockFlag() != null && row.getLockFlag() == AiConstants.FLAG_YES) {
				throw new ServiceException("「" + row.getName() + "」已锁定。请先解锁再删除");
			}
			rows.add(row);
		}
		for (AiMcpTool row : rows) {
			mapper.deleteById(row.getId());
		}
		return rows;
	}

	public Map<String, Object> parse(Long id) {
		moduleService.requireEnabled();
		AiMcpTool tool = require(id);
		String transport = resolveTransport(tool);
		boolean remote = isRemote(transport);
		if ("stdio".equals(transport)) {
			throw new ServiceException("STDIO 还不能在页面里解析工具。请改成 HTTP 并填上能访问的地址");
		}
		if (remote) {
			if (tool.getSseUrl() == null || tool.getSseUrl().isBlank()) {
				throw new ServiceException("请先填写地址。需要以 http:// 或 https:// 开头，再解析");
			}
			try {
				WebClient.RequestHeadersSpec<?> req = webClientBuilder.build().get().uri(tool.getSseUrl().trim());
				if (tool.getApiKey() != null && !tool.getApiKey().isBlank()) {
					req = req.header("Authorization", "Bearer " + tool.getApiKey());
				}
				String body = req.retrieve().bodyToMono(String.class).block(Duration.ofSeconds(8));
				if (body == null || body.isBlank()) {
					throw new ServiceException("这次没有解析到工具。请确认地址返回的是工具清单，再试一次");
				}
				tool.setTransport(transport);
				tool.setToolsJson(body.length() > 8000 ? body.substring(0, 8000) : body);
				tool.sanitizeForUpdate();
				mapper.update(tool);
				mask(tool);
				return Map.of("ok", true, "message", "已解析。清单会显示在这个工具上", "toolsJson", tool.getToolsJson(), "transport", transport);
			} catch (ServiceException e) {
				throw e;
			} catch (Exception ignored) {
				throw new ServiceException("这次没有解析成功。请检查地址能不能从本机访问，再试一次");
			}
		}
		String toolsJson = tool.getToolsJson() == null || tool.getToolsJson().isBlank()
			? "[{\"name\":\"echo\",\"description\":\"local echo\"}]" : tool.getToolsJson();
		tool.setToolsJson(toolsJson);
		tool.sanitizeForUpdate();
		mapper.update(tool);
		mask(tool);
		return Map.of("ok", true, "message", "已解析本地工具。页面调试只会回显，不会调用外部服务",
			"toolsJson", toolsJson, "transport", "local");
	}

	public Map<String, Object> debug(Long id, Map<String, Object> args) {
		moduleService.requireEnabled();
		AiMcpTool tool = require(id);
		String transport = resolveTransport(tool);
		if ("stdio".equals(transport)) {
			return Map.of("ok", false, "message", "这个类型还不能在页面里直接调试。请改成 HTTP 并填上能访问的地址");
		}
		Map<String, Object> result = invoke(id, "echo", args);
		if (Boolean.TRUE.equals(result.get("ok")) && "local".equals(String.valueOf(result.get("transport")))) {
			return Map.of("ok", true, "message", "已回显。这是本地调试，没有调用外部工具", "transport", "local");
		}
		if (!Boolean.TRUE.equals(result.get("ok"))) {
			return Map.of("ok", false, "message", "这次没有调通。请检查地址能不能从本机访问，再试一次");
		}
		return Map.of("ok", true, "message", "已调通。可以在编排里使用这个工具", "transport", String.valueOf(result.get("transport")));
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
					"error", "还没有填写地址。请先编辑这个工具，填上能访问的地址");
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
			} catch (Exception ignored) {
				return Map.of("ok", false, "tool", name, "transport", "http",
					"error", "这次没有调通。请检查地址能不能从本机访问，再试一次");
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

	public AiMcpTool setDefault(Long id) {
		moduleService.requireEnabled();
		AiMcpTool tool = require(id);
		if (tool.getStatus() != null && tool.getStatus() == 0) {
			throw new ServiceException("这个工具已停用。请先启用再设为默认");
		}
		List<AiMcpTool> current = mapper.selectListByQuery(QueryWrapper.create().eq("default_flag", AiConstants.FLAG_YES));
		for (AiMcpTool other : current) {
			if (!other.getId().equals(id)) {
				other.setDefaultFlag(AiConstants.FLAG_NO);
				other.sanitizeForUpdate();
				mapper.update(other);
			}
		}
		tool.setDefaultFlag(AiConstants.FLAG_YES);
		tool.sanitizeForUpdate();
		mapper.update(tool);
		return tool;
	}

	public AiMcpTool lock(Long id, Integer lockFlag) {
		moduleService.requireEnabled();
		if (lockFlag == null || (lockFlag != 0 && lockFlag != 1)) {
			throw new ServiceException("锁定请选择锁定或解锁");
		}
		AiMcpTool tool = require(id);
		tool.setLockFlag(lockFlag);
		tool.sanitizeForUpdate();
		mapper.update(tool);
		return tool;
	}

	public AiMcpTool setStatus(Long id, Integer status) {
		moduleService.requireEnabled();
		if (status == null || (status != 0 && status != 1)) {
			throw new ServiceException("状态请选择启用或停用");
		}
		AiMcpTool tool = require(id);
		tool.setStatus(status);
		if (status == 0) {
			tool.setDefaultFlag(AiConstants.FLAG_NO);
		}
		tool.sanitizeForUpdate();
		mapper.update(tool);
		return tool;
	}

	/** 对外暴露页：已启用、且不是纯本地 echo 的工具。 */
	public List<AiMcpTool> exposed() {
		moduleService.requireEnabled();
		List<AiMcpTool> list = mapper.selectListByQuery(
			QueryWrapper.create().eq("status", AiConstants.STATUS_ENABLE).orderBy("id", false));
		list.removeIf(tool -> !isRemote(resolveTransport(tool)));
		list.forEach(this::mask);
		return list;
	}

	private static boolean isRemote(String transport) {
		if (transport == null) {
			return false;
		}
		String value = transport.trim().toLowerCase();
		return "http".equals(value) || "sse".equals(value) || "streamable".equals(value);
	}

	private static String resolveTransport(AiMcpTool tool) {
		String transport = tool.getTransport() == null ? "" : tool.getTransport().trim().toLowerCase();
		if ((transport.isBlank() || "local".equals(transport))
			&& tool.getSseUrl() != null && !tool.getSseUrl().isBlank()) {
			return "http";
		}
		return transport.isBlank() ? "local" : transport;
	}

	private void requireUnique(String name, Long id) {
		QueryWrapper query = QueryWrapper.create().eq("name", name);
		if (id != null) {
			query.ne("id", id);
		}
		if (mapper.selectCountByQuery(query) > 0) {
			throw new ServiceException("已经有叫「" + name + "」的 MCP。请换一个名称，或打开原来那个");
		}
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
