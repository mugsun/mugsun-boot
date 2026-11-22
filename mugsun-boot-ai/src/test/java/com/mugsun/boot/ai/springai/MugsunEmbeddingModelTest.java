package com.mugsun.boot.ai.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.boot.ai.service.AiModelBizService;
import com.mugsun.boot.ai.support.AiLlmClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spring AI 向量模型：请求经真实的模型客户端发到本机的假模型服务。
 */
class MugsunEmbeddingModelTest {

	private FakeModelServer server;
	private AiModelBizService models;
	private MugsunEmbeddingModel embeddingModel;

	@BeforeEach
	void setUp() throws Exception {
		server = new FakeModelServer();
		models = Mockito.mock(AiModelBizService.class);
		embeddingModel = new MugsunEmbeddingModel(models, FakeModelServer.llm());
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	private void defaultModel(Integer dimensions) {
		AiModel model = server.model("embedding", "default-embedding");
		model.setDimensions(dimensions);
		Mockito.when(models.requireDefaultEmbedding()).thenReturn(model);
	}

	@Test
	@DisplayName("批量向量化：一次请求发出全部文本，按顺序拿回向量")
	void embedsBatch() {
		defaultModel(2);
		server.json("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]},{\"index\":1,\"embedding\":[0.3,0.4]}]}");

		List<float[]> vectors = embeddingModel.embed(List.of("甲", "乙"));

		assertThat(vectors).hasSize(2);
		assertThat(vectors.get(0)).containsExactly(0.1f, 0.2f);
		assertThat(vectors.get(1)).containsExactly(0.3f, 0.4f);
		assertThat(server.requests).hasSize(1);
		JsonNode request = server.requests.get(0);
		assertThat(request.path("model").asText()).isEqualTo("default-embedding");
		assertThat(request.at("/input/0").asText()).isEqualTo("甲");
		assertThat(request.at("/input/1").asText()).isEqualTo("乙");
		assertThat(server.authorizations).containsExactly("Bearer test-key");
	}

	@Test
	@DisplayName("选项里写了模型名称：按名称找向量模型，结果里带实际用的模型标识")
	void modelPickedByName() {
		Mockito.when(models.requireActiveByName("embedding", "小的那个"))
			.thenReturn(server.model("embedding", "small-1"));
		server.json("{\"data\":[{\"index\":0,\"embedding\":[1,2,3]}]}");

		EmbeddingResponse response = embeddingModel.call(new EmbeddingRequest(List.of("甲"),
			EmbeddingOptions.builder().model("小的那个").dimensions(8).build()));

		assertThat(response.getResults()).hasSize(1);
		assertThat(response.getResult().getIndex()).isZero();
		assertThat(response.getResult().getOutput()).containsExactly(1f, 2f, 3f);
		assertThat(response.getMetadata().getModel()).isEqualTo("small-1");
		assertThat(server.requests.get(0).path("model").asText()).isEqualTo("small-1");
		assertThat(server.requests.get(0).has("dimensions")).as("维度以配置页为准，选项里的不发").isFalse();
		Mockito.verify(models, Mockito.never()).requireDefaultEmbedding();
	}

	@Test
	@DisplayName("文档向量化：用的是文档的正文")
	void embedsDocument() {
		defaultModel(2);
		server.json("{\"data\":[{\"index\":0,\"embedding\":[0.5,0.6]}]}");

		float[] vector = embeddingModel.embed(new Document("正文内容"));

		assertThat(vector).containsExactly(0.5f, 0.6f);
		assertThat(server.requests.get(0).at("/input/0").asText()).isEqualTo("正文内容");
	}

	@Test
	@DisplayName("回来的条数和发去的对不上：报错，不把错位的向量交出去")
	void countMismatchIsAnError() {
		defaultModel(2);
		server.json("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2]}]}");

		assertThatThrownBy(() -> embeddingModel.embed(List.of("甲", "乙")))
			.isInstanceOf(AiLlmClient.CallFailedException.class)
			.hasMessageContaining("发了 2 条，回了 1 条");
	}

	@Test
	@DisplayName("维度：配置页填了就用填的，不发请求；没填才问一次模型")
	void dimensionsComeFromConfig() {
		defaultModel(1024);
		assertThat(embeddingModel.dimensions()).isEqualTo(1024);
		assertThat(server.requests).isEmpty();

		defaultModel(null);
		server.json("{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}");
		assertThat(embeddingModel.dimensions()).isEqualTo(3);
		assertThat(server.requests).hasSize(1);
	}

	@Test
	@DisplayName("模型服务报错：报「向量化失败」，类型和平台自己的知识库一致")
	void failureIsReported() {
		defaultModel(2);
		server.status(500, "{\"error\":{\"message\":\"boom\"}}");

		assertThatThrownBy(() -> embeddingModel.embed("甲"))
			.isInstanceOf(AiLlmClient.CallFailedException.class).hasMessageStartingWith("向量化失败");
	}
}
