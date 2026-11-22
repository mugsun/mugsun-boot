package com.mugsun.boot.ai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.core.i18n.message.I18n;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.mugsun.boot.ai.AiConstants;

/**
 * OpenAI 兼容 HTTP 客户端：探测、同步补全、SSE 流式。
 * <p>普通与流式调用都走 {@link OutboundWebClientFactory#create()}（模型 baseUrl 由租户配置，必须按出站规则校验）。
 */
@Component
public class AiLlmClient {

	/** 非流式对话最多等多久 */
	private static final Duration CHAT_TIMEOUT = Duration.ofSeconds(60);

	private final OutboundWebClientFactory outboundWebClients;
	private final ObjectMapper objectMapper;

	public AiLlmClient(OutboundWebClientFactory outboundWebClients, ObjectMapper objectMapper) {
		this.outboundWebClients = outboundWebClients;
		this.objectMapper = objectMapper;
	}

	/**
	 * 调模型、调向量接口没有成功。消息后半句是对方返回的原始报错，给配置模型的人排查用；
	 * 聊天界面不把它直接给提问的人看，要按这个类型认，不要按消息开头的字认（消息会跟着语言变）。
	 */
	public static class CallFailedException extends ServiceException {

		public CallFailedException(String message) {
			super(message);
		}
	}

	public boolean probe(AiModel model) {
		try {
			if (AiConstants.MODEL_TYPE_EMBEDDING.equalsIgnoreCase(
				model.getModelType() == null ? "" : model.getModelType())) {
				float[] v = embed(model, "ping");
				return v != null && v.length > 0;
			}
			String reply = chat(model, List.of(Map.of("role", "user", "content", "ping")), 8);
			return reply != null;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * OpenAI 兼容 /embeddings；返回首条向量。维度由模型决定（Ollama nomic-embed-text=768）。
	 */
	public float[] embed(AiModel model, String text) {
		List<float[]> list = embedBatch(model, List.of(text == null ? "" : text));
		return list.isEmpty() ? new float[0] : list.get(0);
	}

	public List<float[]> embedBatch(AiModel model, List<String> texts) {
		if (texts == null || texts.isEmpty()) {
			return List.of();
		}
		ObjectNode body = objectMapper.createObjectNode();
		body.put("model", modelCode(model));
		ArrayNode input = body.putArray("input");
		for (String t : texts) {
			input.add(t == null ? "" : t);
		}
		String url = baseUrl(model) + "/embeddings";
		try {
			String raw = client(model).post()
				.uri(url)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body.toString())
				.retrieve()
				.bodyToMono(String.class)
				.block(Duration.ofSeconds(120));
			JsonNode root = objectMapper.readTree(raw);
			JsonNode data = root.path("data");
			List<float[]> out = new ArrayList<>(texts.size());
			if (data.isArray()) {
				for (JsonNode item : data) {
					JsonNode emb = item.path("embedding");
					float[] vec = new float[emb.size()];
					for (int i = 0; i < emb.size(); i++) {
						vec[i] = (float) emb.get(i).asDouble();
					}
					out.add(vec);
				}
			}
			return out;
		} catch (Exception e) {
			throw new CallFailedException(I18n.get("向量化失败: {0}", e.getMessage()));
		}
	}

	public String chat(AiModel model, List<Map<String, String>> messages, int maxTokens) {
		JsonNode root = complete(model, chatBody(messages, maxTokens), CHAT_TIMEOUT);
		JsonNode content = root.path("choices").path(0).path("message").path("content");
		return content.isMissingNode() ? "" : content.asText("");
	}

	/**
	 * 按 OpenAI 兼容格式发一次对话请求，返回整段响应。{@code body} 里放 messages、tools、temperature 这些，
	 * model 和 stream 两项由这里填；{@code timeout} 是最多等多久。
	 */
	public JsonNode complete(AiModel model, ObjectNode body, Duration timeout) {
		String url = baseUrl(model) + "/chat/completions";
		try {
			String raw = client(model).post()
				.uri(url)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(requestBody(model, body, false).toString())
				.retrieve()
				.bodyToMono(String.class)
				.block(timeout);
			return objectMapper.readTree(raw);
		} catch (Exception e) {
			throw new CallFailedException(I18n.get("模型调用失败: {0}", e.getMessage()));
		}
	}

	/**
	 * 流式：对每个 SSE data 行回调；data 为 [DONE] 时结束。
	 */
	public void stream(AiModel model, List<Map<String, String>> messages, int maxTokens,
					   Consumer<String> onData, Consumer<Throwable> onError, Runnable onComplete) {
		try {
			streamData(model, chatBody(messages, maxTokens))
				.doOnNext(onData)
				.doOnError(onError)
				.doOnComplete(onComplete)
				.blockLast(Duration.ofMinutes(5));
		} catch (Exception e) {
			onError.accept(e);
		}
	}

	/**
	 * 流式对话：逐条给出 SSE 里 data 行的内容（不含结尾的 [DONE]），订阅后才发请求。{@code body} 的写法和
	 * {@link #complete} 一样。出错时原样向下游抛，由调用方决定怎么报；等多久也由调用方定。
	 */
	public Flux<String> streamData(AiModel model, ObjectNode body) {
		String url = baseUrl(model) + "/chat/completions";
		return client(model).post()
			.uri(url)
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.TEXT_EVENT_STREAM)
			.bodyValue(requestBody(model, body, true).toString())
			.retrieve()
			.bodyToFlux(String.class)
			.concatMapIterable(AiLlmClient::dataLines);
	}

	/** 把收到的一块拆成 data 行；遇到 [DONE] 后，这一块剩下的不再看 */
	private static List<String> dataLines(String chunk) {
		List<String> out = new ArrayList<>();
		for (String line : chunk.split("\n")) {
			String t = line.trim();
			if (t.startsWith("data:")) {
				String data = t.substring(5).trim();
				if ("[DONE]".equals(data)) {
					break;
				}
				out.add(data);
			} else if (!t.isEmpty() && t.startsWith("{")) {
				out.add(t);
			}
		}
		return out;
	}

	private ObjectNode chatBody(List<Map<String, String>> messages, int maxTokens) {
		ObjectNode body = objectMapper.createObjectNode();
		if (maxTokens > 0) {
			body.put("max_tokens", maxTokens);
		}
		ArrayNode msgs = body.putArray("messages");
		for (Map<String, String> m : messages) {
			ObjectNode n = msgs.addObject();
			n.put("role", m.getOrDefault("role", "user"));
			n.put("content", m.getOrDefault("content", ""));
		}
		return body;
	}

	/** 请求体：model、stream 排在最前，并且以这里填的为准；其余照 body 原样带上 */
	private ObjectNode requestBody(AiModel model, ObjectNode body, boolean stream) {
		ObjectNode request = objectMapper.createObjectNode();
		request.put("model", modelCode(model));
		request.put("stream", stream);
		if (body != null) {
			for (Iterator<String> names = body.fieldNames(); names.hasNext(); ) {
				String name = names.next();
				if (!"model".equals(name) && !"stream".equals(name)) {
					request.set(name, body.get(name));
				}
			}
		}
		return request;
	}

	public String extractDeltaContent(String dataJson) {
		try {
			JsonNode root = objectMapper.readTree(dataJson);
			JsonNode delta = root.path("choices").path(0).path("delta");
			JsonNode c = delta.path("content");
			return c.isMissingNode() || c.isNull() ? "" : c.asText("");
		} catch (Exception e) {
			return "";
		}
	}

	public String extractDeltaThink(String dataJson) {
		try {
			JsonNode root = objectMapper.readTree(dataJson);
			JsonNode delta = root.path("choices").path(0).path("delta");
			if (delta.has("reasoning_content")) {
				return delta.path("reasoning_content").asText("");
			}
			return "";
		} catch (Exception e) {
			return "";
		}
	}

	public int[] extractUsage(String dataJson) {
		try {
			JsonNode u = objectMapper.readTree(dataJson).path("usage");
			if (u.isMissingNode()) {
				return null;
			}
			return new int[]{
				u.path("prompt_tokens").asInt(0),
				u.path("completion_tokens").asInt(0),
				u.path("total_tokens").asInt(0)
			};
		} catch (Exception e) {
			return null;
		}
	}

	private WebClient client(AiModel model) {
		// 统一外呼客户端：逐请求校验 + 钉 IP + 不跟随 3xx；内网自部署模型（如 Ollama）经 allow-hosts 放行
		return outboundWebClients.create().mutate()
			.defaultHeader("Authorization", "Bearer " + (model.getApiKey() == null ? "" : model.getApiKey()))
			.build();
	}

	private String baseUrl(AiModel model) {
		String u = model.getBaseUrl();
		if (u == null || u.isBlank()) {
			u = "https://api.openai.com/v1";
		}
		u = u.trim();
		if (u.endsWith("/")) {
			u = u.substring(0, u.length() - 1);
		}
		if (!u.endsWith("/v1") && !u.contains("/v1/")) {
			// 不少兼容端点已含 /v1
		}
		return u;
	}

	private String modelCode(AiModel model) {
		if (model.getModelCode() != null && !model.getModelCode().isBlank()) {
			return model.getModelCode();
		}
		return model.getModelName();
	}
}
