package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiBill;
import com.mugsun.boot.ai.service.AiChatBizService;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/system/ai/billing")
@SaCheckLogin
public class AiBillingController {

	private final AiChatBizService chatService;

	public AiBillingController(AiChatBizService chatService) {
		this.chatService = chatService;
	}

	@GetMapping("/page")
	@SaCheckPermission(AiConstants.PERM_BILLING_LIST)
	public R<Page<AiBill>> page(@RequestParam(defaultValue = "1") long pageNum,
								@RequestParam(defaultValue = "20") long pageSize) {
		return R.data(chatService.billPage(pageNum, pageSize));
	}

	@GetMapping("/trend")
	@SaCheckPermission(AiConstants.PERM_BILLING_LIST)
	public R<List<Map<String, Object>>> trend(@RequestParam(defaultValue = "30") int days) {
		return R.data(chatService.billTrend(days));
	}
}
