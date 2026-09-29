package com.mugsun.boot.ai.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiBill;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.boot.ai.entity.AiSecret;
import com.mugsun.boot.ai.mapper.AiBillMapper;
import com.mugsun.boot.ai.service.AiModelBizService;
import com.mugsun.boot.ai.service.AiQuotaBizService;
import com.mugsun.boot.ai.service.AiSecretBizService;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.ai.support.OpenApiGate;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容对外接口：Bearer sk- 鉴权，不走 Sa-Token 登录；含 IP/速率/配额/账单审计。
 */
@RestController
@RequestMapping("/v1")
public class AiOpenApiController {

	private final AiSecretBizService secretBizService;
	private final AiModelBizService modelBizService;
	private final AiQuotaBizService quotaBizService;
	private final AiBillMapper billMapper;
	private final AiLlmClient llmClient;
	private final ObjectMapper objectMapper;

	public AiOpenApiController(AiSecretBizService secretBizService, AiModelBizService modelBizService,
							   AiQuotaBizService quotaBizService, AiBillMapper billMapper,
							   AiLlmClient llmClient, ObjectMapper objectMapper) {
		this.secretBizService = secretBizService;
		this.modelBizService = modelBizService;
		this.quotaBizService = quotaBizService;
		this.billMapper = billMapper;
		this.llmClient = llmClient;
		this.objectMapper = objectMapper;
	}

	@PostMapping(value = "/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
	public Map<String, Object> chatCompletions(HttpServletRequest request, @RequestBody String raw) throws Exception {
		AiSecret secret = secretBizService.authenticate(request.getHeader("Authorization"));
		return TenantContext.execute(secret.getTenantId(), () -> {
			try {
				OpenApiGate.checkIpAndRate(secret, request);
				quotaBizService.assertWithinQuota(secret.getTenantId(), 256);

				JsonNode root = objectMapper.readTree(raw);
				boolean stream = root.path("stream").asBoolean(false);
				if (stream) {
					throw new ServiceException("OpenAPI 流式请改用管理端 SSE；本端点当前返回同步补全");
				}
				String modelCode = root.path("model").asText("");
				AiModel model = resolveModel(modelCode);
				List<Map<String, String>> messages = new ArrayList<>();
				ArrayNode arr = (ArrayNode) root.path("messages");
				if (arr != null) {
					for (JsonNode n : arr) {
						messages.add(Map.of(
							"role", n.path("role").asText("user"),
							"content", n.path("content").asText("")));
					}
				}
				int maxTokens = root.path("max_tokens").asInt(1024);
				String content = llmClient.chat(model, messages, maxTokens);
				int promptTokens = estimateTokens(messages);
				int completionTokens = Math.max(1, content == null ? 0 : content.length() / 4);
				int total = promptTokens + completionTokens;
				quotaBizService.consume(secret.getTenantId(), total);
				saveBill(secret, model, promptTokens, completionTokens, total, OpenApiGate.clientIp(request));

				ObjectNode resp = objectMapper.createObjectNode();
				resp.put("id", "chatcmpl-" + secret.getId() + "-" + System.currentTimeMillis());
				resp.put("object", "chat.completion");
				resp.put("model", model.getModelCode() != null ? model.getModelCode() : model.getModelName());
				ArrayNode choices = resp.putArray("choices");
				ObjectNode choice = choices.addObject();
				choice.put("index", 0);
				ObjectNode msg = choice.putObject("message");
				msg.put("role", "assistant");
				msg.put("content", content);
				choice.put("finish_reason", "stop");
				ObjectNode usage = resp.putObject("usage");
				usage.put("prompt_tokens", promptTokens);
				usage.put("completion_tokens", completionTokens);
				usage.put("total_tokens", total);
				return objectMapper.convertValue(resp, Map.class);
			} catch (ServiceException e) {
				throw e;
			} catch (Exception e) {
				throw new ServiceException(e.getMessage() == null ? "OpenAPI 调用失败" : e.getMessage());
			}
		});
	}

	@GetMapping("/models")
	public Map<String, Object> models(HttpServletRequest request) {
		AiSecret secret = secretBizService.authenticate(request.getHeader("Authorization"));
		return TenantContext.execute(secret.getTenantId(), () -> {
			OpenApiGate.checkIpAndRate(secret, request);
			List<AiModel> list = modelBizService.listActive("chat");
			ObjectNode resp = objectMapper.createObjectNode();
			resp.put("object", "list");
			ArrayNode data = resp.putArray("data");
			for (AiModel m : list) {
				ObjectNode item = data.addObject();
				item.put("id", m.getModelCode() != null ? m.getModelCode() : m.getModelName());
				item.put("object", "model");
				item.put("owned_by", m.getProvider() != null ? m.getProvider() : "mugsun");
			}
			return objectMapper.convertValue(resp, Map.class);
		});
	}

	private AiModel resolveModel(String modelCode) {
		if (modelCode != null && !modelCode.isBlank()) {
			List<AiModel> list = modelBizService.listActive("chat");
			for (AiModel m : list) {
				String id = m.getModelCode() != null ? m.getModelCode() : m.getModelName();
				if (modelCode.equals(id) || modelCode.equals(String.valueOf(m.getId()))) {
					return modelBizService.requireRaw(m.getId());
				}
			}
		}
		return modelBizService.requireDefaultChat();
	}

	private int estimateTokens(List<Map<String, String>> messages) {
		int n = 0;
		for (Map<String, String> m : messages) {
			String c = m.get("content");
			n += c == null ? 0 : Math.max(1, c.length() / 4);
		}
		return Math.max(1, n);
	}

	private void saveBill(AiSecret secret, AiModel model, int prompt, int completion, int total, String ip) {
		AiBill bill = new AiBill();
		bill.sanitizeForInsert();
		bill.setTenantId(secret.getTenantId());
		bill.setModelId(model.getId());
		bill.setModelName(model.getModelName());
		bill.setPromptTokens(prompt);
		bill.setCompletionTokens(completion);
		bill.setTotalTokens(total);
		bill.setAmount(BigDecimal.ZERO);
		bill.setIp(ip);
		bill.setBizType(AiConstants.SOURCE_OPENAPI);
		bill.setCallTime(LocalDateTime.now());
		billMapper.insert(bill);
	}
}
