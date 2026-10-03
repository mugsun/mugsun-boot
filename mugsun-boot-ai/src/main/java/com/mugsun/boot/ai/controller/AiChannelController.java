package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiChannelBind;
import com.mugsun.boot.ai.service.AiChannelBizService;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/system/ai/channel")
@SaCheckLogin
public class AiChannelController {

	private final AiChannelBizService service;

	public AiChannelController(AiChannelBizService service) {
		this.service = service;
	}

	@GetMapping("/page")
	@SaCheckPermission(AiConstants.PERM_CHANNEL_LIST)
	public R<Page<AiChannelBind>> page(@RequestParam(defaultValue = "1") long pageNum,
									   @RequestParam(defaultValue = "20") long pageSize) {
		return R.data(service.page(pageNum, pageSize));
	}

	@PostMapping("/submit")
	@SaCheckPermission(AiConstants.PERM_CHANNEL_SAVE)
	public R<AiChannelBind> submit(@RequestBody AiChannelBind body) {
		return R.data(service.submit(body));
	}

	@PostMapping("/remove")
	@SaCheckPermission(AiConstants.PERM_CHANNEL_REMOVE)
	public R<Void> remove(@RequestParam(required = false) String ids,
						 @RequestBody(required = false) Map<String, Object> body) {
		service.remove(com.mugsun.boot.ai.support.AiIds.parse(ids, body));
		return R.success("删除成功");
	}

	@PostMapping("/debug")
	@SaCheckPermission(AiConstants.PERM_CHANNEL_DEBUG)
	public R<Map<String, Object>> debug(@RequestParam(required = false) Long id,
										@RequestBody(required = false) Map<String, Object> body) {
		Long real = id;
		if (real == null && body != null && body.get("id") != null) {
			real = Long.valueOf(String.valueOf(body.get("id")));
		}
		if (real == null) {
			throw new com.mugsun.core.tool.exception.ServiceException("缺少渠道 id");
		}
		return R.data(service.debug(real));
	}
}
