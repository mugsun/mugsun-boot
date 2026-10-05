package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiSecret;
import com.mugsun.boot.ai.service.AiSecretBizService;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

	@RepeatSubmit
	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_SECRET_SAVE)
	public R<Map<String, Object>> submit(@RequestBody(required = false) AiSecret body) {
		boolean create = body == null || body.getId() == null;
		Map<String, Object> saved = service.create(body);
		R<Map<String, Object>> result = R.data(saved);
		result.setMsg(create
			? "已创建。密钥只显示这一次，请立刻复制保存"
			: "已保存。列表里的说明会更新，密钥本身不变");
		return result;
	}

	@RepeatSubmit
	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_SECRET_REMOVE)
	public R<Void> remove(@RequestParam(required = false) String ids,
						 @RequestBody(required = false) Map<String, Object> body) {
		List<AiSecret> removed = service.remove(parseIds(ids, body));
		if (removed.size() == 1) {
			return R.success("已删除「" + removed.get(0).getDescription() + "」。这把密钥不能再调用");
		}
		return R.success("已删除 " + removed.size() + " 把密钥。这些密钥不能再调用");
	}

	@RepeatSubmit
	@PostMapping("/status")
	@SaCheckPermission(AiConstants.PERM_SECRET_STATUS)
	public R<Void> status(@RequestParam(required = false) String id,
						 @RequestParam(required = false) String status,
						 @RequestBody(required = false) Map<String, Object> body) {
		Object rawId = id;
		Object rawStatus = status;
		if (body != null) {
			if (rawId == null || String.valueOf(rawId).isBlank()) {
				rawId = body.get("id");
			}
			if (rawStatus == null || String.valueOf(rawStatus).isBlank()) {
				rawStatus = body.get("status");
			}
		}
		if (rawId == null || String.valueOf(rawId).isBlank()) {
			throw new ServiceException("请先在列表里选择要改状态的密钥");
		}
		if (rawStatus == null || !String.valueOf(rawStatus).matches("[01]")) {
			throw new ServiceException("状态请选择启用或停用");
		}
		int value = Integer.parseInt(String.valueOf(rawStatus));
		service.status(parseId(rawId), value);
		return R.success(value == 1
			? "已启用。这把密钥可以继续调用"
			: "已停用。调用会被拒绝");
	}

	private static List<Long> parseIds(String ids, Map<String, Object> body) {
		String raw = ids;
		if ((raw == null || raw.isBlank()) && body != null) {
			Object value = body.get("ids") != null ? body.get("ids") : body.get("id");
			raw = value == null ? null : String.valueOf(value);
		}
		if (raw == null || raw.isBlank() || "null".equals(raw)) {
			throw new ServiceException("请先选择要删除的密钥");
		}
		List<Long> idList = new ArrayList<>();
		for (String part : raw.split(",")) {
			if (part == null || part.isBlank()) {
				continue;
			}
			idList.add(parseId(part));
		}
		if (idList.isEmpty()) {
			throw new ServiceException("请先选择要删除的密钥");
		}
		return idList;
	}

	private static Long parseId(Object raw) {
		String text = String.valueOf(raw).trim();
		if (!text.matches("\\d{1,19}")) {
			throw new ServiceException("密钥编号请填写数字，可以从列表里复制");
		}
		return Long.valueOf(text);
	}
}
