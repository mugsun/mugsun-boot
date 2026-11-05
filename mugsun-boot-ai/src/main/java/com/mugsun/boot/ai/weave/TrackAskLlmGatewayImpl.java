package com.mugsun.boot.ai.weave;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.boot.ai.service.AiModelBizService;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.common.module.TrackAskIntent;
import com.mugsun.boot.common.module.TrackAskLlmGateway;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用默认聊天模型把中文提问抽成结构化意图；失败抛错由 TrackAskService 降级，不填指标。
 */
@Component
public class TrackAskLlmGatewayImpl implements TrackAskLlmGateway {

	private static final Set<String> INTENTS = Set.of(
		"overview", "trend", "pages", "online", "geo", "events", "vitals", "errors", "funnel", "retention",
		TrackAskIntent.UNSUPPORTED);

	private static final String SYSTEM = """
		你是埋点分析意图抽取器。只输出一个 JSON 对象，不要 markdown，不要解释。
		字段：intent, days, eventName, dimType, dimKey, steps, windowHours, fingerprint, routePath, limit。
		intent 只能是：overview,trend,pages,online,geo,events,vitals,errors,funnel,retention,unsupported。
		无法判断时 intent=unsupported。不要编造任何指标数字。steps 为逗号分隔事件名。""";

	private final AiModuleService moduleService;
	private final AiModelBizService modelBizService;
	private final AiLlmClient llmClient;
	private final ObjectMapper objectMapper;

	public TrackAskLlmGatewayImpl(AiModuleService moduleService, AiModelBizService modelBizService,
								  AiLlmClient llmClient, ObjectMapper objectMapper) {
		this.moduleService = moduleService;
		this.modelBizService = modelBizService;
		this.llmClient = llmClient;
		this.objectMapper = objectMapper;
	}

	@Override
	public TrackAskIntent extract(String question, Integer daysHint) {
		moduleService.requireEnabled();
		AiModel model = modelBizService.requireDefaultChat();
		String user = "用户问题：" + question
			+ (daysHint == null ? "" : ("\n请求天数提示：" + daysHint));
		String text = llmClient.chat(model, List.of(
			Map.of("role", "system", "content", SYSTEM),
			Map.of("role", "user", "content", user)
		), 256);
		return parse(text, daysHint);
	}

	private TrackAskIntent parse(String text, Integer daysHint) {
		if (text == null || text.isBlank()) {
			return TrackAskIntent.unsupported("模型未返回意图");
		}
		String json = extractJson(text.trim());
		try {
			JsonNode root = objectMapper.readTree(json);
			String intent = root.path("intent").asText(TrackAskIntent.UNSUPPORTED).trim().toLowerCase();
			if (!INTENTS.contains(intent)) {
				intent = TrackAskIntent.UNSUPPORTED;
			}
			Map<String, Object> params = new LinkedHashMap<>();
			params.put("days", daysHint != null ? daysHint : intOrNull(root.get("days")));
			params.put("eventName", textOrNull(root.get("eventName")));
			params.put("dimType", textOrNull(root.get("dimType")));
			params.put("dimKey", textOrNull(root.get("dimKey")));
			params.put("steps", textOrNull(root.get("steps")));
			params.put("windowHours", longOrNull(root.get("windowHours")));
			params.put("fingerprint", textOrNull(root.get("fingerprint")));
			params.put("routePath", textOrNull(root.get("routePath")));
			params.put("limit", intOrNull(root.get("limit")));
			return new TrackAskIntent(intent, params, null);
		} catch (Exception e) {
			return TrackAskIntent.unsupported("模型返回无法解析为意图 JSON");
		}
	}

	private static String extractJson(String text) {
		int start = text.indexOf('{');
		int end = text.lastIndexOf('}');
		if (start >= 0 && end > start) {
			return text.substring(start, end + 1);
		}
		return text;
	}

	private static String textOrNull(JsonNode n) {
		if (n == null || n.isNull() || n.isMissingNode()) {
			return null;
		}
		String s = n.asText("").trim();
		return s.isEmpty() || "null".equalsIgnoreCase(s) ? null : s;
	}

	private static Integer intOrNull(JsonNode n) {
		if (n == null || n.isNull() || n.isMissingNode()) {
			return null;
		}
		if (n.isNumber()) {
			return n.intValue();
		}
		try {
			return Integer.parseInt(n.asText().trim());
		} catch (Exception e) {
			return null;
		}
	}

	private static Long longOrNull(JsonNode n) {
		if (n == null || n.isNull() || n.isMissingNode()) {
			return null;
		}
		if (n.isNumber()) {
			return n.longValue();
		}
		try {
			return Long.parseLong(n.asText().trim());
		} catch (Exception e) {
			return null;
		}
	}
}
