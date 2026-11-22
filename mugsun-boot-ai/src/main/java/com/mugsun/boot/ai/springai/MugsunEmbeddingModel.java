package com.mugsun.boot.ai.springai;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.boot.ai.service.AiModelBizService;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.core.i18n.message.I18n;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI 的向量模型，接的是「大模型配置」里配好的向量模型。
 * <p>用哪个模型的规则和 {@link MugsunChatModel} 一样：选项里写了 {@code model} 就按名称找，没写用当前租户的默认向量模型。
 * 维度以配置页上填的为准，选项里的 {@code dimensions} 不会发给模型。请求同样经 {@link AiLlmClient} 发出。
 */
@Component
public class MugsunEmbeddingModel implements EmbeddingModel {

	private final AiModelBizService models;
	private final AiLlmClient llm;

	public MugsunEmbeddingModel(AiModelBizService models, AiLlmClient llm) {
		this.models = models;
		this.llm = llm;
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		EmbeddingOptions options = request.getOptions();
		AiModel model = model(options == null ? null : options.getModel());
		List<String> texts = request.getInstructions();
		List<float[]> vectors = llm.embedBatch(model, texts);
		if (vectors.size() != texts.size()) {
			throw new AiLlmClient.CallFailedException(I18n.get(
				"向量模型返回的条数和发去的对不上：发了 {0} 条，回了 {1} 条",
				String.valueOf(texts.size()), String.valueOf(vectors.size())));
		}
		List<Embedding> embeddings = new ArrayList<>(vectors.size());
		for (int i = 0; i < vectors.size(); i++) {
			embeddings.add(new Embedding(vectors.get(i), i));
		}
		String code = StringUtils.hasText(model.getModelCode()) ? model.getModelCode() : model.getModelName();
		return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata(code, new EmptyUsage()));
	}

	@Override
	public float[] embed(Document document) {
		return embed(getEmbeddingContent(document));
	}

	/** 当前租户默认向量模型的维度，取配置页上填的值，不发请求 */
	@Override
	public int dimensions() {
		Integer dimensions = models.requireDefaultEmbedding().getDimensions();
		return dimensions != null && dimensions > 0 ? dimensions : EmbeddingModel.super.dimensions();
	}

	private AiModel model(String name) {
		return StringUtils.hasText(name)
			? models.requireActiveByName(AiConstants.MODEL_TYPE_EMBEDDING, name)
			: models.requireDefaultEmbedding();
	}
}
