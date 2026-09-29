package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiApp;
import com.mugsun.boot.ai.entity.AiAppRun;
import com.mugsun.boot.ai.service.AiAppBizService;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

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
			return R.fail("缺少 id");
		}
		return R.data(service.detail(real));
	}

	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_APP_SAVE)
	public R<AiApp> submit(@RequestBody AiApp body) {
		return R.data(service.submit(body));
	}

	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_APP_REMOVE)
	public R<Void> remove(@RequestParam(required = false) String ids, @RequestBody(required = false) Map<String, Object> body) {
		String raw = ids;
		if ((raw == null || raw.isBlank()) && body != null && body.get("id") != null) {
			raw = String.valueOf(body.get("id"));
		}
		if (raw == null || raw.isBlank()) {
			return R.fail("缺少 id");
		}
		service.remove(Arrays.stream(raw.split(",")).filter(s -> !s.isBlank()).map(Long::valueOf).collect(Collectors.toList()));
		return R.success("删除成功");
	}

	@PostMapping("/copy")
	@SaCheckPermission(AiConstants.PERM_APP_COPY)
	public R<AiApp> copy(@RequestParam(required = false) Long id, @RequestBody(required = false) Map<String, Object> body) {
		Long real = id != null ? id : (body == null || body.get("id") == null ? null : Long.valueOf(String.valueOf(body.get("id"))));
		return R.data(service.copy(real));
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

	@GetMapping("/export")
	@SaCheckPermission(AiConstants.PERM_APP_EXPORT)
	public R<Map<String, Object>> export(@RequestParam Long id) {
		Map<String, Object> resp = new HashMap<>();
		resp.put("dsl", service.exportDsl(id));
		resp.put("id", id);
		return R.data(resp);
	}

	@PostMapping("/import")
	@SaCheckPermission(AiConstants.PERM_APP_IMPORT)
	public R<AiApp> importApp(@RequestBody Map<String, Object> body) {
		return R.data(service.importDsl(
			body.get("name") == null ? null : String.valueOf(body.get("name")),
			body.get("appType") == null ? null : String.valueOf(body.get("appType")),
			body.get("dsl") == null ? String.valueOf(body.getOrDefault("dslJson", "{}")) : String.valueOf(body.get("dsl"))));
	}
}
