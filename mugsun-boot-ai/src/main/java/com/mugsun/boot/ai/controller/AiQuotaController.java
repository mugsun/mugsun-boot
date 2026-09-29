package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiQuota;
import com.mugsun.boot.ai.service.AiQuotaBizService;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/system/ai/quota")
@SaCheckLogin
public class AiQuotaController {

	private final AiQuotaBizService service;

	public AiQuotaController(AiQuotaBizService service) {
		this.service = service;
	}

	@GetMapping("/page")
	@SaCheckPermission(AiConstants.PERM_QUOTA_LIST)
	public R<Page<AiQuota>> page(@RequestParam(defaultValue = "1") long pageNum,
								 @RequestParam(defaultValue = "20") long pageSize) {
		return R.data(service.page(pageNum, pageSize));
	}

	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_QUOTA_SAVE)
	public R<AiQuota> submit(@RequestBody AiQuota body) {
		return R.data(service.submit(body));
	}

	@GetMapping("/trend")
	@SaCheckPermission(AiConstants.PERM_QUOTA_LIST)
	public R<List<Map<String, Object>>> trend() {
		return R.data(service.trend());
	}
}
