package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.boot.ai.service.AiModelBizService;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

	@PostMapping("/submit")
	@SaCheckPermission(value = {AiConstants.PERM_MODEL_SAVE, AiConstants.PERM_MODEL_LIST}, mode = SaMode.OR)
	public R<AiModel> submit(@RequestBody AiModel body) {
		if (body.getId() == null) {
			StpUtil.checkPermission(AiConstants.PERM_MODEL_SAVE);
		} else {
			StpUtil.checkPermission(AiConstants.PERM_MODEL_SAVE);
		}
		return R.data(modelService.submit(body));
	}

	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_MODEL_REMOVE)
	public R<Void> remove(@RequestParam(required = false) String ids,
						  @RequestBody(required = false) Map<String, Object> body) {
		String raw = ids;
		if ((raw == null || raw.isBlank()) && body != null) {
			Object v = body.get("ids") != null ? body.get("ids") : body.get("id");
			raw = v == null ? null : String.valueOf(v);
		}
		if (raw == null || raw.isBlank()) {
			throw new ServiceException("缺少模型 id");
		}
		List<Long> idList = Arrays.stream(raw.split(",")).filter(s -> !s.isBlank())
			.map(Long::valueOf).collect(Collectors.toList());
		modelService.remove(idList);
		return R.success("删除成功");
	}

	@PostMapping("/default")
	@SaCheckPermission(AiConstants.PERM_MODEL_DEFAULT)
	public R<Void> setDefault(@RequestParam(required = false) Long id,
							  @RequestBody(required = false) Map<String, Object> body) {
		modelService.setDefault(requireId(id, body));
		return R.success("已设为默认");
	}

	@PostMapping("/test")
	@SaCheckPermission(AiConstants.PERM_MODEL_TEST)
	public R<Map<String, Object>> test(@RequestParam(required = false) Long id,
										@RequestBody(required = false) Map<String, Object> body) {
		return R.data(modelService.test(requireId(id, body)));
	}

	private static Long requireId(Long id, Map<String, Object> body) {
		if (id != null) {
			return id;
		}
		if (body != null && body.get("id") != null) {
			return Long.valueOf(String.valueOf(body.get("id")));
		}
		throw new ServiceException("缺少模型 id");
	}
}
