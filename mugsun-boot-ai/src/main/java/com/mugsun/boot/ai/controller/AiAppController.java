package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiApp;
import com.mugsun.boot.ai.entity.AiAppRun;
import com.mugsun.boot.ai.service.AiAppBizService;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.mugsun.boot.log.OperationLog;

@RestController
@RequestMapping("/system/ai/app")
@SaCheckLogin
public class AiAppController {

	private final AiAppBizService service;

	public AiAppController(AiAppBizService service) {
		this.service = service;
	}

	@GetMapping("/page")
	@SaCheckPermission(AiConstants.PERM_APP_LIST)
	public R<Page<AiApp>> page(@RequestParam(defaultValue = "1") long pageNum,
							   @RequestParam(defaultValue = "20") long pageSize,
							   @RequestParam(required = false) String name,
							   @RequestParam(required = false) String appType) {
		return R.data(service.page(pageNum, pageSize, name, appType));
	}

	@GetMapping({"/detail", "/detail/{id}"})
	@SaCheckPermission(AiConstants.PERM_APP_LIST)
	public R<AiApp> detail(@PathVariable(required = false) Long id,
						   @RequestParam(value = "id", required = false) Long queryId) {
		Long real = id != null ? id : queryId;
		if (real == null) {
			throw new ServiceException("请从列表中选择一个应用后再查看");
		}
		return R.data(service.detail(real));
	}

	@RepeatSubmit
	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_APP_SAVE)
	public R<AiApp> submit(@RequestBody(required = false) AiApp body) {
		boolean create = body == null || body.getId() == null;
		AiApp saved = service.submit(body);
		R<AiApp> result = R.data(saved);
		result.setMsg(create
			? "已创建。可以点编排继续设计"
			: "已保存。列表会显示最新内容");
		return result;
	}

	@RepeatSubmit
	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_APP_REMOVE)
	public R<Void> remove(@RequestParam(required = false) String ids, @RequestBody(required = false) Map<String, Object> body) {
		String raw = ids;
		if ((raw == null || raw.isBlank()) && body != null && body.get("id") != null) {
			raw = String.valueOf(body.get("id"));
		}
		if (raw == null || raw.isBlank()) {
			throw new ServiceException("请先选择要删除的应用");
		}
		List<Long> parsed = new ArrayList<>();
		for (String part : raw.split(",")) {
			if (part == null || part.isBlank()) {
				continue;
			}
			parsed.add(parseId(part));
		}
		List<AiApp> removed = service.remove(parsed);
		if (removed.size() == 1) {
			return R.success("已删除「" + removed.get(0).getName() + "」。列表里不会再出现");
		}
		return R.success("已删除 " + removed.size() + " 个应用。列表里不会再出现");
	}

	@RepeatSubmit
	@PostMapping("/copy")
	@SaCheckPermission(AiConstants.PERM_APP_COPY)
	public R<AiApp> copy(@RequestParam(required = false) String id, @RequestBody(required = false) Map<String, Object> body) {
		Object raw = id;
		if (raw == null && body != null) {
			raw = body.get("id");
		}
		if (raw == null || String.valueOf(raw).isBlank()) {
			throw new ServiceException("请先在列表里选择要复制的应用");
		}
		AiApp copied = service.copy(parseId(raw));
		R<AiApp> result = R.data(copied);
		result.setMsg("已复制「" + copied.getName() + "」。列表里可以打开这份副本");
		return result;
	}

	private static Long parseId(Object raw) {
		String text = String.valueOf(raw).trim();
		if (!text.matches("\\d{1,19}")) {
			throw new ServiceException("应用编号请填写数字，可以从列表里复制");
		}
		return Long.valueOf(text);
	}

	@PostMapping("/dsl/save")
	@SaCheckPermission(AiConstants.PERM_APP_DESIGN)
	public R<AiApp> saveDsl(@RequestParam(required = false) Long id, @RequestBody Map<String, Object> body) {
		Long real = id;
		if (real == null && body.get("id") != null) {
			real = Long.valueOf(String.valueOf(body.get("id")));
		}
		Object dsl = body.get("dsl");
		if (dsl == null) {
			dsl = body.get("dslJson");
		}
		return R.data(service.saveDsl(real, dsl == null ? null : String.valueOf(dsl)));
	}

	@PostMapping("/run")
	@SaCheckPermission(AiConstants.PERM_APP_RUN)
	public R<Map<String, Object>> run(@RequestParam(required = false) Long id, @RequestBody(required = false) Map<String, Object> body) {
		Long real = id;
		Map<String, Object> input = body == null ? new HashMap<>() : new HashMap<>(body);
		if (real == null && input.get("id") != null) {
			real = Long.valueOf(String.valueOf(input.remove("id")));
		}
		input.remove("dslJson");
		input.remove("dsl");
		return R.data(service.run(real, input));
	}

	@GetMapping("/run/page")
	@SaCheckPermission(AiConstants.PERM_APP_LIST)
	public R<Page<AiAppRun>> runPage(@RequestParam(defaultValue = "1") long pageNum,
									 @RequestParam(defaultValue = "20") long pageSize,
									 @RequestParam(required = false) Long appId) {
		return R.data(service.runPage(pageNum, pageSize, appId));
	}

	@GetMapping("/run/detail")
	@SaCheckPermission(AiConstants.PERM_APP_LIST)
	public R<Map<String, Object>> runDetail(@RequestParam Long runId) {
		return R.data(service.runDetail(runId));
	}

	@PostMapping("/share")
	@SaCheckPermission(AiConstants.PERM_APP_PUBLISH)
	public R<Map<String, Object>> share(@RequestBody Map<String, Object> body) {
		Long id = Long.valueOf(String.valueOf(body.get("id")));
		boolean enable = body.get("enable") == null || Boolean.parseBoolean(String.valueOf(body.get("enable")));
		return R.data(service.share(id, enable));
	}

	@OperationLog("导出 AI 应用")
	@GetMapping("/export")
	@SaCheckPermission(AiConstants.PERM_APP_EXPORT)
	public R<Map<String, Object>> export(@RequestParam Long id) {
		Map<String, Object> resp = new HashMap<>();
		resp.put("dsl", service.exportDsl(id));
		resp.put("id", id);
		return R.data(resp);
	}

	@OperationLog("导入 AI 应用")
	@PostMapping("/import")
	@SaCheckPermission(AiConstants.PERM_APP_IMPORT)
	public R<AiApp> importApp(@RequestBody Map<String, Object> body) {
		return R.data(service.importDsl(
			body.get("name") == null ? null : String.valueOf(body.get("name")),
			body.get("appType") == null ? null : String.valueOf(body.get("appType")),
			body.get("dsl") == null ? String.valueOf(body.getOrDefault("dslJson", "{}")) : String.valueOf(body.get("dsl"))));
	}
}
