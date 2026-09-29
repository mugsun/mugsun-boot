package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiSecret;
import com.mugsun.boot.ai.service.AiSecretBizService;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/system/ai/secret")
@SaCheckLogin
public class AiSecretController {

	private final AiSecretBizService service;

	public AiSecretController(AiSecretBizService service) {
		this.service = service;
	}

	@GetMapping("/page")
	@SaCheckPermission(AiConstants.PERM_SECRET_LIST)
	public R<Page<AiSecret>> page(@RequestParam(defaultValue = "1") long pageNum,
								  @RequestParam(defaultValue = "20") long pageSize) {
		return R.data(service.page(pageNum, pageSize));
	}

	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_SECRET_SAVE)
	public R<Map<String, Object>> submit(@RequestBody AiSecret body) {
		return R.data(service.create(body));
	}

	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_SECRET_REMOVE)
	public R<Void> remove(@RequestParam String ids) {
		service.remove(Arrays.stream(ids.split(",")).filter(s -> !s.isBlank()).map(Long::valueOf).collect(Collectors.toList()));
		return R.success("删除成功");
	}

	@PostMapping("/status")
	@SaCheckPermission(AiConstants.PERM_SECRET_STATUS)
	public R<Void> status(@RequestParam Long id, @RequestParam Integer status) {
		service.status(id, status);
		return R.success("ok");
	}
}
