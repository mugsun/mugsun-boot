package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiMcpTool;
import com.mugsun.boot.ai.service.AiMcpBizService;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/system/ai/mcp")
@SaCheckLogin
public class AiMcpController {

	private final AiMcpBizService service;

	public AiMcpController(AiMcpBizService service) {
		this.service = service;
	}

	@GetMapping("/page")
	@SaCheckPermission(AiConstants.PERM_MCP_LIST)
	public R<Page<AiMcpTool>> page(@RequestParam(defaultValue = "1") long pageNum,
								   @RequestParam(defaultValue = "20") long pageSize,
								   @RequestParam(required = false) String name) {
		return R.data(service.page(pageNum, pageSize, name));
	}

	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_MCP_SAVE)
	public R<AiMcpTool> submit(@RequestBody AiMcpTool body) {
		return R.data(service.submit(body));
	}

	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_MCP_REMOVE)
	public R<Void> remove(@RequestParam(required = false) String ids,
						 @RequestBody(required = false) Map<String, Object> body) {
		service.remove(com.mugsun.boot.ai.support.AiIds.parse(ids, body));
		return R.success("删除成功");
	}

	@PostMapping("/parse")
	@SaCheckPermission(AiConstants.PERM_MCP_PARSE)
	public R<Map<String, Object>> parse(@RequestParam(required = false) Long id,
										@RequestBody(required = false) Map<String, Object> body) {
		return R.data(service.parse(mcpId(id, body)));
	}

	@PostMapping("/debug")
	@SaCheckPermission(AiConstants.PERM_MCP_DEBUG)
	public R<Map<String, Object>> debug(@RequestParam(required = false) Long id,
										 @RequestBody(required = false) Map<String, Object> args) {
		return R.data(service.debug(mcpId(id, args), args));
	}

	@PostMapping("/default")
	@SaCheckPermission(AiConstants.PERM_MCP_DEFAULT)
	public R<Void> setDefault(@RequestParam(required = false) Long id,
							  @RequestBody(required = false) Map<String, Object> body) {
		service.setDefault(com.mugsun.boot.ai.support.AiIds.one(id, body));
		return R.success("已设为默认");
	}

	@PostMapping("/lock")
	@SaCheckPermission(AiConstants.PERM_MCP_LOCK)
	public R<Void> lock(@RequestBody(required = false) Map<String, Object> body) {
		if (body == null || body.get("id") == null) {
			throw new com.mugsun.core.tool.exception.ServiceException("缺少 MCP id");
		}
		Integer flag = body.get("lockFlag") == null ? 1 : Integer.valueOf(String.valueOf(body.get("lockFlag")));
		service.lock(Long.valueOf(String.valueOf(body.get("id"))), flag);
		return R.success("已更新");
	}

	@GetMapping("/server/list")
	@SaCheckPermission(AiConstants.PERM_MCP_SERVER)
	public R<java.util.List<AiMcpTool>> servers() {
		return R.data(service.exposed());
	}

	private static Long mcpId(Long id, Map<String, Object> body) {
		if (id != null) {
			return id;
		}
		if (body != null && body.get("id") != null) {
			return Long.valueOf(String.valueOf(body.get("id")));
		}
		throw new com.mugsun.core.tool.exception.ServiceException("缺少 MCP id");
	}
}
