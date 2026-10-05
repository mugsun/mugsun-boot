package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.service.AiChatBizService;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
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
	public SseEmitter stream(@RequestBody(required = false) JsonNode body) {
		if (body == null || !body.isObject()) {
			throw new ServiceException("请先填写要发送的内容");
		}
		Long sessionId = requiredId(body, "sessionId", "请先在列表里选择会话，或新建一个再发送",
			"会话编号请填写数字，可以从列表里复制");
		Long modelId = optionalId(body, "modelId", "模型编号请填写数字，可以从模型列表里复制");
		Long knowledgeId = optionalId(body, "knowledgeId", "知识库编号请填写数字，可以从知识库列表里复制");
		String content = text(body, "content");
		String requestId = text(body, "requestId");
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
	public R<Void> stop(@RequestParam(required = false) String requestId,
						@RequestBody(required = false) Map<String, Object> body) {
		if ((requestId == null || requestId.isBlank()) && body != null && body.get("requestId") != null) {
			requestId = String.valueOf(body.get("requestId"));
		}
		return R.success(chatService.stop(requestId));
	}

	private static Long requiredId(JsonNode body, String field, String empty, String hint) {
		JsonNode node = body.get(field);
		if (node == null || node.isNull() || node.asText("").isBlank()) {
			throw new ServiceException(empty);
		}
		return parseId(node.asText(), hint);
	}

	private static Long optionalId(JsonNode body, String field, String hint) {
		JsonNode node = body.get(field);
		if (node == null || node.isNull() || node.asText("").isBlank()) {
			return null;
		}
		return parseId(node.asText(), hint);
	}

	private static String text(JsonNode body, String field) {
		JsonNode node = body.get(field);
		if (node == null || node.isNull()) {
			return null;
		}
		return node.asText();
	}

	private static Long parseId(String raw, String hint) {
		String text = raw == null ? "" : raw.trim();
		if (!text.matches("\\d{1,19}")) {
			throw new ServiceException(hint);
		}
		return Long.valueOf(text);
	}
}
