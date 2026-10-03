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
	public R<Void> remove(@RequestParam(required = false) String ids,
						 @RequestBody(required = false) Map<String, Object> body) {
		service.remove(com.mugsun.boot.ai.support.AiIds.parse(ids, body));
		return R.success("删除成功");
	}

	@PostMapping("/status")
	@SaCheckPermission(AiConstants.PERM_SECRET_STATUS)
	public R<Void> status(@RequestParam(required = false) Long id,
						 @RequestParam(required = false) Integer status,
						 @RequestBody(required = false) Map<String, Object> body) {
		Long realId = id;
		Integer realStatus = status;
		if (body != null) {
			if (realId == null && body.get("id") != null) {
				realId = Long.valueOf(String.valueOf(body.get("id")));
			}
			if (realStatus == null && body.get("status") != null) {
				realStatus = Integer.valueOf(String.valueOf(body.get("status")));
			}
		}
		if (realId == null || realStatus == null) {
			throw new com.mugsun.core.tool.exception.ServiceException("缺少密钥 id 或状态");
		}
		service.status(realId, realStatus);
		return R.success("ok");
	}
}
