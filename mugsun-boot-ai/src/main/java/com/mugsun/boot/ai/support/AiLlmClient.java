package com.mugsun.boot.ai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.mugsun.boot.ai.AiConstants;

/**
 * OpenAI 兼容 HTTP 客户端：探测、同步补全、SSE 流式。
 */
@Component
public class AiLlmClient {

	private final WebClient.Builder webClientBuilder;
	private final ObjectMapper objectMapper;

	public AiLlmClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
		this.webClientBuilder = webClientBuilder;
		this.objectMapper = objectMapper;
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
			throw new ServiceException("向量化失败: " + e.getMessage());
		}
	}

	public String chat(AiModel model, List<Map<String, String>> messages, int maxTokens) {
		ObjectNode body = objectMapper.createObjectNode();
		body.put("model", modelCode(model));
		body.put("stream", false);
		if (maxTokens > 0) {
			body.put("max_tokens", maxTokens);
		}
		ArrayNode msgs = body.putArray("messages");
		for (Map<String, String> m : messages) {
			ObjectNode n = msgs.addObject();
			n.put("role", m.getOrDefault("role", "user"));
			n.put("content", m.getOrDefault("content", ""));
		}
		String url = baseUrl(model) + "/chat/completions";
		try {
			String raw = client(model).post()
				.uri(url)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body.toString())
				.retrieve()
				.bodyToMono(String.class)
				.block(Duration.ofSeconds(60));
			JsonNode root = objectMapper.readTree(raw);
			JsonNode content = root.path("choices").path(0).path("message").path("content");
			return content.isMissingNode() ? "" : content.asText("");
		} catch (Exception e) {
			throw new ServiceException("模型调用失败: " + e.getMessage());
		}
	}

	/**
	 * 流式：对每个 SSE data 行回调；data 为 [DONE] 时结束。
	 */
	public void stream(AiModel model, List<Map<String, String>> messages, int maxTokens,
					   Consumer<String> onData, Consumer<Throwable> onError, Runnable onComplete) {
		ObjectNode body = objectMapper.createObjectNode();
		body.put("model", modelCode(model));
		body.put("stream", true);
		if (maxTokens > 0) {
			body.put("max_tokens", maxTokens);
		}
		ArrayNode msgs = body.putArray("messages");
		for (Map<String, String> m : messages) {
			ObjectNode n = msgs.addObject();
			n.put("role", m.getOrDefault("role", "user"));
			n.put("content", m.getOrDefault("content", ""));
		}
		String url = baseUrl(model) + "/chat/completions";
		try {
			Flux<String> flux = client(model).post()
				.uri(url)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.TEXT_EVENT_STREAM)
				.bodyValue(body.toString())
				.retrieve()
				.bodyToFlux(String.class);
			flux.doOnNext(chunk -> {
					for (String line : chunk.split("\n")) {
						String t = line.trim();
						if (t.startsWith("data:")) {
							String data = t.substring(5).trim();
							if ("[DONE]".equals(data)) {
								return;
							}
							onData.accept(data);
						} else if (!t.isEmpty() && t.startsWith("{")) {
							onData.accept(t);
						}
					}
				})
				.doOnError(onError)
				.doOnComplete(onComplete)
				.blockLast(Duration.ofMinutes(5));
		} catch (Exception e) {
			onError.accept(e);
		}
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
		return webClientBuilder.build().mutate()
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
