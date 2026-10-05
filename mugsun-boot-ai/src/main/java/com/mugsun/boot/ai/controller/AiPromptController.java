package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiPrompt;
import com.mugsun.boot.ai.service.AiPromptBizService;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

	@RepeatSubmit
	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_PROMPT_SAVE)
	public R<AiPrompt> submit(@RequestBody(required = false) AiPrompt body) {
		boolean create = body == null || body.getId() == null;
		AiPrompt saved = service.submit(body);
		R<AiPrompt> result = R.data(saved);
		result.setMsg(create
			? "已添加。列表里可以打开，也能继续优化"
			: "已保存。列表会显示最新内容");
		return result;
	}

	@RepeatSubmit
	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_PROMPT_REMOVE)
	public R<Void> remove(@RequestParam(required = false) String ids,
						 @RequestBody(required = false) Map<String, Object> body) {
		String raw = ids;
		if ((raw == null || raw.isBlank()) && body != null) {
			Object value = body.get("ids") != null ? body.get("ids") : body.get("id");
			raw = value == null ? null : String.valueOf(value);
		}
		if (raw == null || raw.isBlank() || "null".equals(raw)) {
			throw new ServiceException("请先选择要删除的提示词");
		}
		List<Long> parsed = new ArrayList<>();
		for (String part : raw.split(",")) {
			if (part == null || part.isBlank()) {
				continue;
			}
			parsed.add(parseId(part));
		}
		List<AiPrompt> removed = service.remove(parsed);
		if (removed.size() == 1) {
			return R.success("已删除「" + removed.get(0).getName() + "」。列表里不会再出现");
		}
		return R.success("已删除 " + removed.size() + " 个提示词。列表里不会再出现");
	}

	@PostMapping("/optimize")
	@SaCheckPermission(AiConstants.PERM_PROMPT_OPTIMIZE)
	public R<Map<String, Object>> optimize(@RequestParam(required = false) String id,
										   @RequestBody(required = false) Map<String, Object> body) {
		Object raw = id;
		if (raw == null && body != null) {
			raw = body.get("id");
		}
		if (raw == null || String.valueOf(raw).isBlank()) {
			throw new ServiceException("请先在列表里选择要优化的提示词");
		}
		R<Map<String, Object>> result = R.data(service.optimize(parseId(raw)));
		result.setMsg("已生成优化结果。确认后点采纳并保存");
		return result;
	}

	private static Long parseId(Object raw) {
		String text = String.valueOf(raw).trim();
		if (!text.matches("\\d{1,19}")) {
			throw new ServiceException("提示词编号请填写数字，可以从列表里复制");
		}
		return Long.valueOf(text);
	}
}
