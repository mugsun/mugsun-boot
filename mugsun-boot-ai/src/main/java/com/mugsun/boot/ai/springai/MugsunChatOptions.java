package com.mugsun.boot.ai.springai;

import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 对话选项。在 Spring AI 的通用选项（模型、温度、工具……）之外多记一项：发起这次请求时的租户。
 * <p>为什么要记：流式对话里，工具和工具之后再问模型的那几轮在别的线程上执行，那里取不到租户，
 * 这几处就按这里记的租户执行，见 {@link TenantScope}。这一项由 {@link MugsunChatModel#getOptions()} 填，
 * 用 {@code ChatClient} 时每次请求都会重新取，不用自己管。
 * <p>选项对象只给当次请求用，不要存起来给别的请求用。
 */
public final class MugsunChatOptions extends DefaultToolCallingChatOptions {

	private final String tenantId;

	private MugsunChatOptions(List<ToolCallback> toolCallbacks, Map<String, Object> toolContext, String model,
							  Double frequencyPenalty, Integer maxTokens, Double presencePenalty,
							  List<String> stopSequences, Double temperature, Integer topK, Double topP,
							  String tenantId) {
		super(bind(toolCallbacks, tenantId), toolContext, model, frequencyPenalty, maxTokens, presencePenalty,
			stopSequences, temperature, topK, topP);
		this.tenantId = tenantId;
	}

	/** 工具也带上这次的租户；这次没有租户时，把以前带的也去掉，不沿用别的请求的租户 */
	private static List<ToolCallback> bind(List<ToolCallback> toolCallbacks, String tenantId) {
		if (toolCallbacks == null) {
			return null;
		}
		List<ToolCallback> bound = new ArrayList<>(toolCallbacks.size());
		for (ToolCallback callback : toolCallbacks) {
			bound.add(TenantBoundToolCallback.of(callback, tenantId));
		}
		return bound;
	}

	/** 发起请求时的租户；当时没有租户则为 {@code null} */
	String tenantId() {
		return tenantId;
	}

	@Override
	public Builder mutate() {
		return new Builder()
			.model(getModel())
			.frequencyPenalty(getFrequencyPenalty())
			.maxTokens(getMaxTokens())
			.presencePenalty(getPresencePenalty())
			.stopSequences(getStopSequences())
			.temperature(getTemperature())
			.topK(getTopK())
			.topP(getTopP())
			.toolCallbacks(getToolCallbacks())
			.toolContext(getToolContext())
			.tenantId(tenantId);
	}

	@Override
	public boolean equals(Object o) {
		return super.equals(o) && Objects.equals(tenantId, ((MugsunChatOptions) o).tenantId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(super.hashCode(), tenantId);
	}

	public static Builder builder() {
		return new Builder();
	}

	/** 用法和 Spring AI 自带的选项构建器一样 */
	public static final class Builder extends DefaultToolCallingChatOptions.Builder<Builder> {

		private String tenantId;

		private Builder() {
		}

		Builder tenantId(String tenantId) {
			this.tenantId = tenantId;
			return this;
		}

		@Override
		public MugsunChatOptions build() {
			return new MugsunChatOptions(this.toolCallbacks, this.toolContext, this.model, this.frequencyPenalty,
				this.maxTokens, this.presencePenalty, this.stopSequences, this.temperature, this.topK, this.topP,
				this.tenantId);
		}
	}
}
