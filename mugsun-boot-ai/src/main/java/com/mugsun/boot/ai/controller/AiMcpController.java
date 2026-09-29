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
	public R<Void> remove(@RequestParam String ids) {
		service.remove(Arrays.stream(ids.split(",")).filter(s -> !s.isBlank()).map(Long::valueOf).collect(Collectors.toList()));
		return R.success("删除成功");
	}

	@PostMapping("/parse")
	@SaCheckPermission(AiConstants.PERM_MCP_PARSE)
	public R<Map<String, Object>> parse(@RequestParam Long id) {
		return R.data(service.parse(id));
	}

	@PostMapping("/debug")
	@SaCheckPermission(AiConstants.PERM_MCP_DEBUG)
	public R<Map<String, Object>> debug(@RequestParam Long id, @RequestBody(required = false) Map<String, Object> args) {
		return R.data(service.debug(id, args));
	}
}
