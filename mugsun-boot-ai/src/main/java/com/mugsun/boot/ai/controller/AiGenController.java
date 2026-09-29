package com.mugsun.boot.ai.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.boot.ai.service.AiModelBizService;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/system/ai/gen")
@SaCheckLogin
public class AiGenController {

	private static final Set<String> TYPES = Set.of(
		"mindmap", "poster", "article", "product", "marketing", "svg", "layout");

	private final AiModuleService moduleService;
	private final AiModelBizService modelBizService;
	private final AiLlmClient llmClient;
	private final ObjectMapper objectMapper;

	public AiGenController(AiModuleService moduleService, AiModelBizService modelBizService,
						   AiLlmClient llmClient, ObjectMapper objectMapper) {
		this.moduleService = moduleService;
		this.modelBizService = modelBizService;
		this.llmClient = llmClient;
		this.objectMapper = objectMapper;
	}

	@PostMapping(value = "/{type}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	@SaCheckPermission(value = {
		AiConstants.PERM_GEN_MINDMAP, AiConstants.PERM_GEN_POSTER, AiConstants.PERM_GEN_ARTICLE,
		AiConstants.PERM_GEN_PRODUCT, AiConstants.PERM_GEN_MARKETING, AiConstants.PERM_GEN_SVG,
		AiConstants.PERM_GEN_LAYOUT
	}, mode = SaMode.OR)
	public SseEmitter stream(@PathVariable String type, @RequestBody Map<String, Object> body) {
		moduleService.requireEnabled();
		if (!TYPES.contains(type)) {
			throw new ServiceException("未知生成器类型");
		}
		// OR 注解只保证「七选一有码」；此处按 path type 二次校验，防跨类型越权
		StpUtil.checkPermission(permForType(type));
		String prompt = buildPrompt(type, body);
		if (prompt.isBlank()) {
			throw new ServiceException("请填写生成主题或需求");
		}
		String system = "你是" + type + "生成器，按用户要求输出结果。";
		Long modelId = body.get("modelId") == null ? null : Long.valueOf(body.get("modelId").toString());
		AiModel model = modelId != null ? modelBizService.requireRaw(modelId) : modelBizService.requireDefaultChat();
		SseEmitter emitter = new SseEmitter(300_000L);
		String tenantId = TenantContext.current();
		final String tid = tenantId == null || tenantId.isBlank() ? "000000" : tenantId;
		Thread.startVirtualThread(() -> TenantContext.execute(tid, () -> {
			try {
				llmClient.stream(model, List.of(
					Map.of("role", "system", "content", system),
					Map.of("role", "user", "content", prompt)
				), 2048, data -> {
					String delta = llmClient.extractDeltaContent(data);
					if (delta != null && !delta.isEmpty()) {
						try {
							emitter.send(SseEmitter.event().name(AiConstants.SSE_CHUNK)
								.data(objectMapper.writeValueAsString(Map.of("content", delta))));
						} catch (Exception ignored) {
						}
					}
				}, err -> {
					try {
						emitter.send(SseEmitter.event().name(AiConstants.SSE_ERROR)
							.data(objectMapper.writeValueAsString(Map.of("message",
								err.getMessage() == null ? "error" : err.getMessage()))));
					} catch (Exception ignored) {
					}
					emitter.completeWithError(err);
				}, () -> {
					try {
						emitter.send(SseEmitter.event().name(AiConstants.SSE_DONE).data("{}"));
					} catch (Exception ignored) {
					}
					emitter.complete();
				});
			} catch (Exception e) {
				emitter.completeWithError(e);
			}
		}));
		return emitter;
	}

	private static String permForType(String type) {
		return switch (type) {
			case "mindmap" -> AiConstants.PERM_GEN_MINDMAP;
			case "poster" -> AiConstants.PERM_GEN_POSTER;
			case "article" -> AiConstants.PERM_GEN_ARTICLE;
			case "product" -> AiConstants.PERM_GEN_PRODUCT;
			case "marketing" -> AiConstants.PERM_GEN_MARKETING;
			case "svg" -> AiConstants.PERM_GEN_SVG;
			case "layout" -> AiConstants.PERM_GEN_LAYOUT;
			default -> throw new ServiceException("未知生成器类型");
		};
	}

	/** 兼容前端 topic/title/extra 与标准 prompt 字段 */
	private static String buildPrompt(String type, Map<String, Object> body) {
		if (body.get("prompt") != null && !body.get("prompt").toString().isBlank()) {
			return body.get("prompt").toString().trim();
		}
		StringBuilder sb = new StringBuilder();
		append(sb, "主题", body.get("topic"));
		append(sb, "标题", body.get("title"));
		append(sb, "副标题", body.get("subtitle"));
		append(sb, "风格", body.get("style"));
		append(sb, "类目", body.get("category"));
		append(sb, "补充", body.get("extra"));
		if (body.get("width") != null || body.get("height") != null) {
			sb.append("尺寸: ").append(body.get("width")).append('x').append(body.get("height")).append('\n');
		}
		sb.append("请按 ").append(type).append(" 类型输出可用结果。");
		return sb.toString().trim();
	}

	private static void append(StringBuilder sb, String label, Object v) {
		if (v == null) {
			return;
		}
		String s = v.toString().trim();
		if (!s.isEmpty()) {
			sb.append(label).append(": ").append(s).append('\n');
		}
	}
}
