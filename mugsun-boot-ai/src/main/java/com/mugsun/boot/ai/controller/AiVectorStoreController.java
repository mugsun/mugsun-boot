package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiVectorStore;
import com.mugsun.boot.ai.service.AiVectorStoreBizService;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
	public R<AiVectorStore> detail(@PathVariable Long id) {
		return R.data(service.detail(id));
	}

	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_VECTOR_SAVE)
	public R<AiVectorStore> submit(@RequestBody AiVectorStore body) {
		return R.data(service.submit(body));
	}

	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_VECTOR_REMOVE)
	public R<Void> remove(@RequestParam String ids) {
		List<Long> idList = Arrays.stream(ids.split(",")).filter(s -> !s.isBlank())
			.map(Long::valueOf).collect(Collectors.toList());
		service.remove(idList);
		return R.success("删除成功");
	}

	@PostMapping("/test")
	@SaCheckPermission(AiConstants.PERM_VECTOR_TEST)
	public R<Map<String, Object>> test(@RequestParam(required = false) Long id,
										@RequestBody(required = false) Map<String, Object> body) {
		return R.data(service.test(bodyId(id, body)));
	}

	private static Long bodyId(Long id, Map<String, Object> body) {
		if (id != null) {
			return id;
		}
		if (body != null && body.get("id") != null) {
			return Long.valueOf(String.valueOf(body.get("id")));
		}
		throw new com.mugsun.core.tool.exception.ServiceException("缺少 id");
	}
}
