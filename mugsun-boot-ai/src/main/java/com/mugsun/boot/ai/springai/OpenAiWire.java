package com.mugsun.boot.ai.springai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.core.i18n.message.I18n;
import com.mugsun.core.tool.exception.ServiceException;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Spring AI 的消息、选项和 OpenAI 兼容格式的 JSON 互相转换。
 */
final class OpenAiWire {

	private OpenAiWire() {
	}

	/** 请求体：选项、消息、工具。model 和 stream 两项不在这里填，由 {@link AiLlmClient} 填 */
	static ObjectNode request(ObjectMapper json, Prompt prompt) {
		ObjectNode body = json.createObjectNode();
		ChatOptions options = prompt.getOptions();
		if (options != null) {
			if (options.getTemperature() != null) {
				body.put("temperature", options.getTemperature());
			}
			if (options.getTopP() != null) {
				body.put("top_p", options.getTopP());
			}
			if (options.getTopK() != null) {
				body.put("top_k", options.getTopK());
			}
			if (options.getMaxTokens() != null) {
				body.put("max_tokens", options.getMaxTokens());
			}
			if (options.getFrequencyPenalty() != null) {
				body.put("frequency_penalty", options.getFrequencyPenalty());
			}
			if (options.getPresencePenalty() != null) {
				body.put("presence_penalty", options.getPresencePenalty());
			}
			if (!CollectionUtils.isEmpty(options.getStopSequences())) {
				ArrayNode stop = body.putArray("stop");
				options.getStopSequences().forEach(stop::add);
			}
		}
		body.set("messages", messages(json, prompt.getInstructions()));
		if (options instanceof ToolCallingChatOptions tools && !CollectionUtils.isEmpty(tools.getToolCallbacks())) {
			body.set("tools", tools(json, tools.getToolCallbacks()));
		}
		return body;
	}

	private static ArrayNode messages(ObjectMapper json, List<Message> messages) {
		ArrayNode out = json.createArrayNode();
		for (Message message : messages) {
			switch (message.getMessageType()) {
				case SYSTEM -> out.addObject().put("role", "system").put("content", text(message));
				case USER -> user(out.addObject(), message);
				case ASSISTANT -> assistant(out.addObject(), message);
				case TOOL -> toolResponses(out, message);
			}
		}
		return out;
	}

	private static void user(ObjectNode node, Message message) {
		node.put("role", "user");
		List<Media> media = message instanceof UserMessage user ? user.getMedia() : List.of();
		if (CollectionUtils.isEmpty(media)) {
			node.put("content", text(message));
			return;
		}
		ArrayNode parts = node.putArray("content");
		if (StringUtils.hasText(message.getText())) {
			parts.addObject().put("type", "text").put("text", message.getText());
		}
		for (Media item : media) {
			parts.addObject().put("type", "image_url").putObject("image_url").put("url", imageUrl(item));
		}
	}

	/** 图片：给的是地址就原样传（由模型服务去取），给的是内容就写成 data 地址。其它类型的附件不支持 */
	private static String imageUrl(Media media) {
		boolean image = media.getMimeType() != null && "image".equalsIgnoreCase(media.getMimeType().getType());
		Object data = media.getData();
		if (image && data instanceof String url) {
			return url;
		}
		if (image && data instanceof byte[] bytes) {
			return "data:" + media.getMimeType() + ";base64," + Base64.getEncoder().encodeToString(bytes);
		}
		throw new ServiceException(I18n.get("发给模型的附件目前只支持图片，不支持 {0}", String.valueOf(media.getMimeType())));
	}

	private static void assistant(ObjectNode node, Message message) {
		node.put("role", "assistant");
		List<AssistantMessage.ToolCall> calls = message instanceof AssistantMessage assistant
			? assistant.getToolCalls() : List.of();
		if (CollectionUtils.isEmpty(calls)) {
			node.put("content", text(message));
			return;
		}
		if (StringUtils.hasText(message.getText())) {
			node.put("content", message.getText());
		} else {
			node.putNull("content");
		}
		ArrayNode toolCalls = node.putArray("tool_calls");
		for (AssistantMessage.ToolCall call : calls) {
			ObjectNode item = toolCalls.addObject();
			item.put("id", call.id());
			item.put("type", "function");
			item.putObject("function").put("name", call.name()).put("arguments", arguments(call.arguments()));
		}
	}

	private static void toolResponses(ArrayNode out, Message message) {
		if (!(message instanceof ToolResponseMessage responses)) {
			return;
		}
		for (ToolResponseMessage.ToolResponse response : responses.getResponses()) {
			out.addObject()
				.put("role", "tool")
				.put("tool_call_id", response.id())
				.put("content", response.responseData() == null ? "" : response.responseData());
		}
	}

	private static ArrayNode tools(ObjectMapper json, List<ToolCallback> callbacks) {
		ArrayNode out = json.createArrayNode();
		for (ToolCallback callback : callbacks) {
			ToolDefinition definition = callback.getToolDefinition();
			ObjectNode function = out.addObject().put("type", "function").putObject("function");
			function.put("name", definition.name());
			if (StringUtils.hasText(definition.description())) {
				function.put("description", definition.description());
			}
			try {
				function.set("parameters", json.readTree(definition.inputSchema()));
			} catch (JsonProcessingException e) {
				throw new IllegalStateException("工具 " + definition.name() + " 的参数说明不是合法的 JSON", e);
			}
		}
		return out;
	}

	/** 整段响应转成 Spring AI 的结果。{@code locale} 用来写报错的话 */
	static ChatResponse response(ObjectMapper json, JsonNode root, Locale locale) {
		failOnError(root, locale);
		String id = text(root.path("id"));
		List<Generation> generations = new ArrayList<>();
		for (JsonNode choice : root.path("choices")) {
			JsonNode message = choice.path("message");
			String finish = finishReason(choice);
			Map<String, Object> properties = new HashMap<>();
			if (!id.isEmpty()) {
				properties.put("id", id);
			}
			properties.put("role", "assistant");
			properties.put("index", choice.path("index").asInt(generations.size()));
			properties.put("finishReason", finish);
			properties.put(MugsunChatModel.REASONING_CONTENT, text(message.path("reasoning_content")));
			AssistantMessage output = AssistantMessage.builder()
				.content(text(message.path("content")))
				.properties(properties)
				.toolCalls(toolCalls(message.path("tool_calls")))
				.build();
			generations.add(new Generation(output, ChatGenerationMetadata.builder().finishReason(finish).build()));
		}
		return new ChatResponse(generations, metadata(id, text(root.path("model")), usage(json, root.path("usage"))));
	}

	/** 有的服务出错时仍然返回 200，错误写在响应体的 error 里 */
	private static void failOnError(JsonNode root, Locale locale) {
		JsonNode error = root.path("error");
		if (root.has("choices") || error.isMissingNode() || error.isNull()) {
			return;
		}
		String detail = error.isObject() && error.path("message").isTextual()
			? error.path("message").asText() : error.toString();
		throw new AiLlmClient.CallFailedException(I18n.get(locale, "模型调用失败: {0}", detail));
	}

	private static List<AssistantMessage.ToolCall> toolCalls(JsonNode calls) {
		List<AssistantMessage.ToolCall> out = new ArrayList<>();
		for (JsonNode call : calls) {
			JsonNode function = call.path("function");
			out.add(new AssistantMessage.ToolCall(callId(text(call.path("id"))), "function",
				text(function.path("name")), arguments(argumentsText(function.path("arguments")))));
		}
		return out;
	}

	private static ChatResponseMetadata metadata(String id, String model, Usage usage) {
		ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
		if (!id.isEmpty()) {
			metadata.id(id);
		}
		if (!model.isEmpty()) {
			metadata.model(model);
		}
		if (usage != null) {
			metadata.usage(usage);
		}
		return metadata.build();
	}

	private static Usage usage(ObjectMapper json, JsonNode usage) {
		if (!usage.isObject()) {
			return null;
		}
		int prompt = usage.path("prompt_tokens").asInt(0);
		int completion = usage.path("completion_tokens").asInt(0);
		int total = usage.path("total_tokens").asInt(prompt + completion);
		JsonNode cached = usage.path("prompt_tokens_details").path("cached_tokens");
		return new DefaultUsage(prompt, completion, total, json.convertValue(usage, Map.class),
			cached.isNumber() ? cached.asLong() : null, null);
	}

	/** 结束原因按 Spring AI 的习惯写成大写（STOP、TOOL_CALLS、LENGTH）；没有就是空串 */
	private static String finishReason(JsonNode choice) {
		return text(choice.path("finish_reason")).toUpperCase(Locale.ROOT);
	}

	private static String text(Message message) {
		return message.getText() == null ? "" : message.getText();
	}

	private static String text(JsonNode node) {
		return node.isTextual() ? node.asText() : "";
	}

	/** 工具参数一般是一段 JSON 文本，个别服务直接给对象 */
	private static String argumentsText(JsonNode node) {
		if (node.isTextual()) {
			return node.asText();
		}
		return node.isObject() ? node.toString() : "";
	}

	/** 没有参数时统一成空对象，工具那边才解析得了 */
	private static String arguments(String arguments) {
		return StringUtils.hasText(arguments) ? arguments : "{}";
	}

	/** 个别服务不给工具调用编号，补一个，回传工具结果时要对得上 */
	private static String callId(String id) {
		return id.isEmpty() ? "call_" + UUID.randomUUID().toString().replace("-", "") : id;
	}

	/**
	 * 流式响应的拼装，一次请求用一个。文字来一段发一段；工具调用是分几段到的，攒齐了一次发出，
	 * 这样下游拿到的每个工具调用都是完整的。
	 */
	static final class Stream {

		private final ObjectMapper json;
		private final Locale locale;
		private final StringBuilder reasoning = new StringBuilder();
		private final TreeMap<Integer, Draft> drafts = new TreeMap<>();
		private final StringBuilder text = new StringBuilder();
		private final List<AssistantMessage.ToolCall> sentCalls = new ArrayList<>();
		private String id = "";
		private String model = "";
		private String lastFinish = "";
		private Usage lastUsage;

		Stream(ObjectMapper json, Locale locale) {
			this.json = json;
			this.locale = locale;
		}

		/** 处理一条 data：有东西要发就返回一个分片，没有就返回空 */
		List<ChatResponse> accept(String data) {
			JsonNode root;
			try {
				root = json.readTree(data);
			} catch (JsonProcessingException e) {
				return List.of();
			}
			failOnError(root, locale);
			if (!text(root.path("id")).isEmpty()) {
				id = text(root.path("id"));
			}
			if (!text(root.path("model")).isEmpty()) {
				model = text(root.path("model"));
			}
			JsonNode choice = root.path("choices").path(0);
			JsonNode delta = choice.path("delta");
			String content = text(delta.path("content"));
			String thinking = text(delta.path("reasoning_content"));
			reasoning.append(thinking);
			for (JsonNode call : delta.path("tool_calls")) {
				collect(call);
			}
			String finish = finishReason(choice);
			Usage usage = usage(json, root.path("usage"));
			List<AssistantMessage.ToolCall> calls = finish.isEmpty() ? List.of() : drain();
			if (content.isEmpty() && thinking.isEmpty() && finish.isEmpty() && usage == null) {
				return List.of();
			}
			return List.of(chunk(content, thinking, finish, calls, usage));
		}

		/** 流结束时调用：服务没给结束原因、工具调用还攒着没发的，在这里补发 */
		List<ChatResponse> finish() {
			List<AssistantMessage.ToolCall> calls = drain();
			return calls.isEmpty() ? List.of() : List.of(chunk("", "", "TOOL_CALLS", calls, null));
		}

		/** 已经发出去的各片拼成一整段：全文、全部工具调用、最后的结束原因和用量。观测数据记的是这一份 */
		ChatResponse aggregated() {
			Map<String, Object> properties = new HashMap<>();
			if (!id.isEmpty()) {
				properties.put("id", id);
			}
			properties.put("role", "assistant");
			properties.put("finishReason", lastFinish);
			properties.put(MugsunChatModel.REASONING_CONTENT, reasoning.toString());
			AssistantMessage output = AssistantMessage.builder()
				.content(text.toString())
				.properties(properties)
				.toolCalls(List.copyOf(sentCalls))
				.build();
			return new ChatResponse(List.of(new Generation(output, generation(lastFinish))),
				metadata(id, model, lastUsage));
		}

		private void collect(JsonNode call) {
			Draft draft = draft(call);
			String callId = text(call.path("id"));
			if (!callId.isEmpty()) {
				draft.id = callId;
			}
			JsonNode function = call.path("function");
			String name = text(function.path("name"));
			if (!name.isEmpty()) {
				draft.name = name;
			}
			draft.arguments.append(argumentsText(function.path("arguments")));
		}

		/** 这一段属于哪个工具调用：先看序号；没有序号看编号；都没有就接在最后一个后面 */
		private Draft draft(JsonNode call) {
			JsonNode index = call.path("index");
			if (index.isInt()) {
				return drafts.computeIfAbsent(index.asInt(), k -> new Draft());
			}
			String callId = text(call.path("id"));
			if (callId.isEmpty() && !drafts.isEmpty()) {
				return drafts.lastEntry().getValue();
			}
			for (Draft draft : drafts.values()) {
				if (!callId.isEmpty() && callId.equals(draft.id)) {
					return draft;
				}
			}
			Draft draft = new Draft();
			drafts.put(drafts.isEmpty() ? 0 : drafts.lastKey() + 1, draft);
			return draft;
		}

		private List<AssistantMessage.ToolCall> drain() {
			List<AssistantMessage.ToolCall> calls = new ArrayList<>(drafts.size());
			for (Draft draft : drafts.values()) {
				calls.add(new AssistantMessage.ToolCall(callId(draft.id), "function", draft.name,
					arguments(draft.arguments.toString())));
			}
			drafts.clear();
			return calls;
		}

		private ChatResponse chunk(String content, String thinking, String finish,
								   List<AssistantMessage.ToolCall> calls, Usage usage) {
			text.append(content);
			sentCalls.addAll(calls);
			if (!finish.isEmpty()) {
				lastFinish = finish;
			}
			if (usage != null) {
				lastUsage = usage;
			}
			Map<String, Object> properties = new HashMap<>();
			if (!id.isEmpty()) {
				properties.put("id", id);
			}
			properties.put("role", "assistant");
			properties.put("finishReason", finish);
			properties.put(MugsunChatModel.REASONING_CONTENT, reasoning.toString());
			properties.put(MugsunChatModel.REASONING_DELTA, thinking);
			AssistantMessage output = AssistantMessage.builder()
				.content(content)
				.properties(properties)
				.toolCalls(calls)
				.build();
			return new ChatResponse(List.of(new Generation(output, generation(finish))), metadata(id, model, usage));
		}

		private static ChatGenerationMetadata generation(String finish) {
			return finish.isEmpty()
				? ChatGenerationMetadata.NULL : ChatGenerationMetadata.builder().finishReason(finish).build();
		}

		private static final class Draft {
			private String id = "";
			private String name = "";
			private final StringBuilder arguments = new StringBuilder();
		}
	}
}
