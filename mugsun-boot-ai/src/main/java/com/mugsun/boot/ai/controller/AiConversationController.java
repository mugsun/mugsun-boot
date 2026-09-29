package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiMessage;
import com.mugsun.boot.ai.entity.AiSession;
import com.mugsun.boot.ai.service.AiChatBizService;
import com.mugsun.core.tool.api.R;
import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/system/ai/conversation")
@SaCheckLogin
public class AiConversationController {

	private final AiChatBizService chatService;

	public AiConversationController(AiChatBizService chatService) {
		this.chatService = chatService;
	}

	@GetMapping("/page")
	@SaCheckPermission(AiConstants.PERM_CONVERSATION_LIST)
	public R<Page<AiSession>> page(@RequestParam(defaultValue = "1") long pageNum,
								   @RequestParam(defaultValue = "20") long pageSize) {
		return R.data(chatService.conversationPage(pageNum, pageSize));
	}

	@GetMapping("/detail")
	@SaCheckPermission(AiConstants.PERM_CONVERSATION_DETAIL)
	public R<List<AiMessage>> detail(@RequestParam Long sessionId) {
		return R.data(chatService.messages(sessionId));
	}
}
