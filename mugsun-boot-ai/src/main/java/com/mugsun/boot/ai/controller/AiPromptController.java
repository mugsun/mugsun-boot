package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiPrompt;
import com.mugsun.boot.ai.service.AiPromptBizService;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/system/ai/prompt")
@SaCheckLogin
public class AiPromptController {

	private final AiPromptBizService service;

	public AiPromptController(AiPromptBizService service) {
		this.service = service;
	}

	@GetMapping("/page")
	@SaCheckPermission(AiConstants.PERM_PROMPT_LIST)
	public R<Page<AiPrompt>> page(@RequestParam(defaultValue = "1") long pageNum,
								  @RequestParam(defaultValue = "20") long pageSize,
								  @RequestParam(required = false) String name,
								  @RequestParam(required = false) String category) {
		return R.data(service.page(pageNum, pageSize, name, category));
	}

	@GetMapping("/detail/{id}")
	@SaCheckPermission(AiConstants.PERM_PROMPT_LIST)
	public R<AiPrompt> detail(@PathVariable Long id) {
		return R.data(service.detail(id));
	}

	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_PROMPT_SAVE)
	public R<AiPrompt> submit(@RequestBody AiPrompt body) {
		return R.data(service.submit(body));
	}

	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_PROMPT_REMOVE)
	public R<Void> remove(@RequestParam String ids) {
		service.remove(Arrays.stream(ids.split(",")).filter(s -> !s.isBlank()).map(Long::valueOf).collect(Collectors.toList()));
		return R.success("删除成功");
	}

	@PostMapping("/optimize")
	@SaCheckPermission(AiConstants.PERM_PROMPT_OPTIMIZE)
	public R<Map<String, Object>> optimize(@RequestParam Long id) {
		return R.data(service.optimize(id));
	}
}
