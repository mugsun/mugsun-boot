package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiVectorStore;
import com.mugsun.boot.ai.service.AiVectorStoreBizService;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/system/ai/vector")
@SaCheckLogin
public class AiVectorStoreController {

	private final AiVectorStoreBizService service;

	public AiVectorStoreController(AiVectorStoreBizService service) {
		this.service = service;
	}

	@GetMapping("/page")
	@SaCheckPermission(AiConstants.PERM_VECTOR_LIST)
	public R<Page<AiVectorStore>> page(@RequestParam(defaultValue = "1") long pageNum,
									   @RequestParam(defaultValue = "20") long pageSize,
									   @RequestParam(required = false) String name) {
		return R.data(service.page(pageNum, pageSize, name));
	}

	@GetMapping("/detail/{id}")
	@SaCheckPermission(AiConstants.PERM_VECTOR_LIST)
	public R<AiVectorStore> detail(@PathVariable String id) {
		return R.data(service.detail(parseId(id)));
	}

	@RepeatSubmit
	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_VECTOR_SAVE)
	public R<AiVectorStore> submit(@RequestBody(required = false) AiVectorStore body) {
		boolean create = body == null || body.getId() == null;
		AiVectorStore saved = service.submit(body);
		R<AiVectorStore> result = R.data(saved);
		result.setMsg(create
			? "已添加。可以点测试连接，通过后知识库就能用来检索"
			: "已保存。列表会显示最新内容");
		return result;
	}

	@RepeatSubmit
	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_VECTOR_REMOVE)
	public R<Void> remove(@RequestParam(required = false) String ids,
						 @RequestBody(required = false) Map<String, Object> body) {
		List<AiVectorStore> removed = service.remove(parseIds(ids, body));
		if (removed.size() == 1) {
			return R.success("已删除「" + removed.get(0).getName() + "」。列表里不会再出现");
		}
		return R.success("已删除 " + removed.size() + " 个向量库。列表里不会再出现");
	}

	@PostMapping("/test")
	@SaCheckPermission(AiConstants.PERM_VECTOR_TEST)
	public R<Map<String, Object>> test(@RequestParam(required = false) String id,
										@RequestBody(required = false) Map<String, Object> body) {
		Object raw = id;
		if ((raw == null || String.valueOf(raw).isBlank()) && body != null) {
			raw = body.get("id");
		}
		if (raw == null || String.valueOf(raw).isBlank()) {
			throw new ServiceException("请先在列表里选择要测试的向量库");
		}
		Map<String, Object> data = service.test(parseId(raw));
		R<Map<String, Object>> result = R.data(data);
		result.setMsg(String.valueOf(data.get("message")));
		if (!Boolean.TRUE.equals(data.get("ok"))) {
			result.setSuccess(false);
			result.setCode(400);
		}
		return result;
	}

	private static List<Long> parseIds(String ids, Map<String, Object> body) {
		String raw = ids;
		if ((raw == null || raw.isBlank()) && body != null) {
			Object value = body.get("ids") != null ? body.get("ids") : body.get("id");
			raw = value == null ? null : String.valueOf(value);
		}
		if (raw == null || raw.isBlank() || "null".equals(raw)) {
			throw new ServiceException("请先选择要删除的向量库");
		}
		List<Long> idList = new ArrayList<>();
		for (String part : raw.split(",")) {
			if (part == null || part.isBlank()) {
				continue;
			}
			idList.add(parseId(part));
		}
		if (idList.isEmpty()) {
			throw new ServiceException("请先选择要删除的向量库");
		}
		return idList;
	}

	private static Long parseId(Object raw) {
		String text = String.valueOf(raw).trim();
		if (!text.matches("\\d{1,19}")) {
			throw new ServiceException("向量库编号请填写数字，可以从列表里复制");
		}
		return Long.valueOf(text);
	}
}
