package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiDashboard;
import com.mugsun.boot.ai.service.AiDashboardBizService;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/system/ai/dashboard")
@SaCheckLogin
public class AiDashboardController {

	private final AiDashboardBizService service;

	public AiDashboardController(AiDashboardBizService service) {
		this.service = service;
	}

	@GetMapping("/page")
	@SaCheckPermission(AiConstants.PERM_DASHBOARD_DESIGN)
	public R<Page<AiDashboard>> page(@RequestParam(defaultValue = "1") long pageNum,
									 @RequestParam(defaultValue = "20") long pageSize) {
		return R.data(service.page(pageNum, pageSize));
	}

	@GetMapping("/detail/{id}")
	@SaCheckPermission(AiConstants.PERM_DASHBOARD_DESIGN)
	public R<AiDashboard> detail(@PathVariable Long id) {
		return R.data(service.detail(id));
	}

	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_DASHBOARD_DESIGN)
	public R<AiDashboard> submit(@RequestBody AiDashboard body) {
		return R.data(service.submit(body));
	}

	@PostMapping("/dsl/save")
	@SaCheckPermission(AiConstants.PERM_DASHBOARD_DESIGN)
	public R<AiDashboard> saveDsl(@RequestParam Long id, @RequestBody Map<String, String> body) {
		return R.data(service.saveDsl(id, body.get("dsl")));
	}
}
