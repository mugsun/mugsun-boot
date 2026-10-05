package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.boot.ai.service.AiModelBizService;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/system/ai/model")
@SaCheckLogin
public class AiModelController {

	private final AiModelBizService modelService;

	public AiModelController(AiModelBizService modelService) {
		this.modelService = modelService;
	}

	@GetMapping("/page")
	@SaCheckPermission(AiConstants.PERM_MODEL_LIST)
	public R<Page<AiModel>> page(@RequestParam(defaultValue = "1") long pageNum,
								 @RequestParam(defaultValue = "20") long pageSize,
								 @RequestParam(required = false) String modelType,
								 @RequestParam(required = false) String name) {
		return R.data(modelService.page(pageNum, pageSize, modelType, name));
	}

	@GetMapping("/list")
	@SaCheckPermission(AiConstants.PERM_MODEL_LIST)
	public R<List<AiModel>> list(@RequestParam(required = false) String modelType) {
		return R.data(modelService.listActive(modelType));
	}

	@GetMapping("/detail/{id}")
	@SaCheckPermission(AiConstants.PERM_MODEL_LIST)
	public R<AiModel> detail(@PathVariable Long id) {
		return R.data(modelService.detail(id));
	}

	@RepeatSubmit
	@PostMapping("/submit")
	@SaCheckPermission(value = {AiConstants.PERM_MODEL_SAVE, AiConstants.PERM_MODEL_LIST}, mode = SaMode.OR)
	public R<AiModel> submit(@RequestBody(required = false) AiModel body) {
		StpUtil.checkPermission(AiConstants.PERM_MODEL_SAVE);
		boolean create = body == null || body.getId() == null;
		AiModel saved = modelService.submit(body);
		R<AiModel> result = R.data(saved);
		result.setMsg(create
			? "已添加。可以点测试，通过后就能设为默认"
			: "已保存。列表会显示最新内容");
		return result;
	}

	@RepeatSubmit
	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_MODEL_REMOVE)
	public R<Void> remove(@RequestParam(required = false) String ids,
						  @RequestBody(required = false) Map<String, Object> body) {
		String raw = ids;
		if ((raw == null || raw.isBlank()) && body != null) {
			Object v = body.get("ids") != null ? body.get("ids") : body.get("id");
			raw = v == null ? null : String.valueOf(v);
		}
		if (raw == null || raw.isBlank() || "null".equals(raw)) {
			throw new ServiceException("请先选择要删除的模型");
		}
		List<Long> idList = new ArrayList<>();
		for (String part : raw.split(",")) {
			if (part == null || part.isBlank()) {
				continue;
			}
			idList.add(parseId(part));
		}
		List<AiModel> removed = modelService.remove(idList);
		if (removed.size() == 1) {
			return R.success("已删除「" + removed.get(0).getModelName() + "」。列表里不会再出现");
		}
		return R.success("已删除 " + removed.size() + " 个模型。列表里不会再出现");
	}

	@RepeatSubmit
	@PostMapping("/default")
	@SaCheckPermission(AiConstants.PERM_MODEL_DEFAULT)
	public R<Void> setDefault(@RequestParam(required = false) String id,
							  @RequestBody(required = false) Map<String, Object> body) {
		Object raw = id;
		if (raw == null && body != null) {
			raw = body.get("id");
		}
		if (raw == null || String.valueOf(raw).isBlank()) {
			throw new ServiceException("请先在列表里选择要设为默认的模型");
		}
		modelService.setDefault(parseId(raw));
		return R.success("已设为默认。同类型的其他模型不再是默认");
	}

	@PostMapping("/test")
	@SaCheckPermission(AiConstants.PERM_MODEL_TEST)
	public R<Map<String, Object>> test(@RequestParam(required = false) String id,
										@RequestBody(required = false) Map<String, Object> body) {
		Object raw = id;
		if (raw == null && body != null) {
			raw = body.get("id");
		}
		if (raw == null || String.valueOf(raw).isBlank()) {
			throw new ServiceException("请先在列表里选择要测试的模型");
		}
		Map<String, Object> data = modelService.test(parseId(raw));
		R<Map<String, Object>> result = R.data(data);
		result.setMsg(String.valueOf(data.get("message")));
		if (!Boolean.TRUE.equals(data.get("ok"))) {
			result.setSuccess(false);
			result.setCode(400);
		}
		return result;
	}

	private static Long parseId(Object raw) {
		String text = String.valueOf(raw).trim();
		if (!text.matches("\\d{1,19}")) {
			throw new ServiceException("模型编号请填写数字，可以从列表里复制");
		}
		return Long.valueOf(text);
	}
}
