package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.service.AiChatBizService;
import com.mugsun.core.tool.api.R;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/system/ai/chat")
@SaCheckLogin
public class AiChatController {

	private final AiChatBizService chatService;

	public AiChatController(AiChatBizService chatService) {
		this.chatService = chatService;
	}

	@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@SaCheckPermission(AiConstants.PERM_ASSISTANT_CHAT)
	public SseEmitter stream(@RequestBody Map<String, Object> body) {
		Long sessionId = body.get("sessionId") == null ? null : Long.valueOf(body.get("sessionId").toString());
		Long modelId = body.get("modelId") == null ? null : Long.valueOf(body.get("modelId").toString());
		Long knowledgeId = body.get("knowledgeId") == null || body.get("knowledgeId").toString().isBlank()
			? null : Long.valueOf(body.get("knowledgeId").toString());
		String content = body.get("content") == null ? "" : body.get("content").toString();
		String requestId = body.get("requestId") == null ? null : body.get("requestId").toString();
		return chatService.stream(sessionId, modelId, content, requestId, knowledgeId);
	}

	@PostMapping("/receive")
	@SaCheckPermission(AiConstants.PERM_ASSISTANT_CHAT)
	public R<Map<String, Object>> receive(@RequestBody Map<String, Object> body) {
		String requestId = body.get("requestId") == null ? "" : body.get("requestId").toString();
		return R.data(chatService.receiveAck(requestId));
	}

	@GetMapping(value = "/replay", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@SaCheckPermission(AiConstants.PERM_ASSISTANT_CHAT)
	public SseEmitter replay(@RequestParam String requestId) {
		return chatService.replay(requestId);
	}

	@PostMapping("/stop")
	@SaCheckPermission(AiConstants.PERM_ASSISTANT_CHAT)
	public R<Void> stop(@RequestParam String requestId) {
		chatService.stop(requestId);
		return R.success("ok");
	}
}
