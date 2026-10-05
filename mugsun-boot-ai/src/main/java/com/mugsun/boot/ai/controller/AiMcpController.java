package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiMcpTool;
import com.mugsun.boot.ai.service.AiMcpBizService;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
								   @RequestParam(required = false) String name,
								   @RequestParam(required = false) String category) {
		return R.data(service.page(pageNum, pageSize, name, category));
	}

	@RepeatSubmit
	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_MCP_SAVE)
	public R<AiMcpTool> submit(@RequestBody(required = false) AiMcpTool body) {
		boolean create = body == null || body.getId() == null;
		AiMcpTool saved = service.submit(body);
		R<AiMcpTool> result = R.data(saved);
		result.setMsg(create
			? "已添加。可以点调试，确认能不能调通"
			: "已保存。列表会显示最新内容");
		return result;
	}

	@RepeatSubmit
	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_MCP_REMOVE)
	public R<Void> remove(@RequestParam(required = false) String ids,
						 @RequestBody(required = false) Map<String, Object> body) {
		List<AiMcpTool> removed = service.remove(parseIds(ids, body));
		if (removed.size() == 1) {
			return R.success("已删除「" + removed.get(0).getName() + "」。列表里不会再出现，也不能再调试");
		}
		return R.success("已删除 " + removed.size() + " 个 MCP。列表里不会再出现");
	}

	@PostMapping("/parse")
	@SaCheckPermission(AiConstants.PERM_MCP_PARSE)
	public R<Map<String, Object>> parse(@RequestParam(required = false) String id,
										@RequestBody(required = false) Map<String, Object> body) {
		Map<String, Object> data = service.parse(requireId(id, body, "请先保存这个工具，再解析清单"));
		R<Map<String, Object>> result = R.data(data);
		result.setMsg(String.valueOf(data.get("message")));
		if (!Boolean.TRUE.equals(data.get("ok"))) {
			result.setSuccess(false);
			result.setCode(400);
		}
		return result;
	}

	@PostMapping("/debug")
	@SaCheckPermission(AiConstants.PERM_MCP_DEBUG)
	public R<Map<String, Object>> debug(@RequestParam(required = false) String id,
										 @RequestBody(required = false) Map<String, Object> args) {
		Map<String, Object> data = service.debug(requireId(id, args, "请先在列表里选择要调试的 MCP"), args);
		R<Map<String, Object>> result = R.data(data);
		result.setMsg(String.valueOf(data.get("message")));
		if (!Boolean.TRUE.equals(data.get("ok"))) {
			result.setSuccess(false);
			result.setCode(400);
		}
		return result;
	}

	@RepeatSubmit
	@PostMapping("/default")
	@SaCheckPermission(AiConstants.PERM_MCP_DEFAULT)
	public R<Void> setDefault(@RequestParam(required = false) String id,
							  @RequestBody(required = false) Map<String, Object> body) {
		AiMcpTool tool = service.setDefault(requireId(id, body, "请先在列表里选择要设为默认的 MCP"));
		return R.success("已把「" + tool.getName() + "」设为默认。编排会优先用它");
	}

	@RepeatSubmit
	@PostMapping("/lock")
	@SaCheckPermission(AiConstants.PERM_MCP_LOCK)
	public R<Void> lock(@RequestBody(required = false) Map<String, Object> body) {
		if (body == null || body.get("id") == null || String.valueOf(body.get("id")).isBlank()) {
			throw new ServiceException("请先在列表里选择要锁定的 MCP");
		}
		if (body.get("lockFlag") == null || !String.valueOf(body.get("lockFlag")).trim().matches("[01]")) {
			throw new ServiceException("锁定请选择锁定或解锁");
		}
		int flag = Integer.parseInt(String.valueOf(body.get("lockFlag")).trim());
		AiMcpTool tool = service.lock(parseId(body.get("id")), flag);
		return R.success(flag == 1
			? "已锁定「" + tool.getName() + "」。编辑和删除会被拦住"
			: "已解锁「" + tool.getName() + "」。可以继续编辑和删除");
	}

	@GetMapping("/server/list")
	@SaCheckPermission(AiConstants.PERM_MCP_SERVER)
	public R<List<AiMcpTool>> servers() {
		return R.data(service.exposed());
	}

	@RepeatSubmit
	@PostMapping("/server/toggle")
	@SaCheckPermission(AiConstants.PERM_MCP_SERVER)
	public R<Void> toggle(@RequestBody(required = false) Map<String, Object> body) {
		if (body == null || body.get("id") == null || String.valueOf(body.get("id")).isBlank()) {
			throw new ServiceException("请先在列表里选择要改状态的 MCP");
		}
		if (body.get("status") == null || String.valueOf(body.get("status")).isBlank()) {
			throw new ServiceException("状态请选择启用或停用");
		}
		int status = parseFlag(body.get("status"));
		AiMcpTool tool = service.setStatus(parseId(body.get("id")), status);
		return R.success(status == 1
			? "已启用「" + tool.getName() + "」。对外暴露列表里还能看到"
			: "已停用「" + tool.getName() + "」。对外暴露列表里不会再出现");
	}

	private static Long requireId(String id, Map<String, Object> body, String emptyHint) {
		Object raw = id;
		if ((raw == null || String.valueOf(raw).isBlank()) && body != null) {
			raw = body.get("id");
		}
		if (raw == null || String.valueOf(raw).isBlank()) {
			throw new ServiceException(emptyHint);
		}
		return parseId(raw);
	}

	private static List<Long> parseIds(String ids, Map<String, Object> body) {
		String raw = ids;
		if ((raw == null || raw.isBlank()) && body != null) {
			Object value = body.get("ids") != null ? body.get("ids") : body.get("id");
			raw = value == null ? null : String.valueOf(value);
		}
		if (raw == null || raw.isBlank() || "null".equals(raw)) {
			throw new ServiceException("请先选择要删除的 MCP");
		}
		List<Long> idList = new ArrayList<>();
		for (String part : raw.split(",")) {
			if (part == null || part.isBlank()) {
				continue;
			}
			idList.add(parseId(part));
		}
		if (idList.isEmpty()) {
			throw new ServiceException("请先选择要删除的 MCP");
		}
		return idList;
	}

	private static Long parseId(Object raw) {
		String text = String.valueOf(raw).trim();
		if (!text.matches("\\d{1,19}")) {
			throw new ServiceException("MCP 编号请填写数字，可以从列表里复制");
		}
		return Long.valueOf(text);
	}

	private static int parseFlag(Object raw) {
		String text = String.valueOf(raw).trim();
		if (!text.matches("[01]")) {
			throw new ServiceException("状态请选择启用或停用");
		}
		return Integer.parseInt(text);
	}
}
