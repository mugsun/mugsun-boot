package com.mugsun.boot.ai.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.ai.service.AiModelBizService;
import com.mugsun.boot.ai.support.AiLlmClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring AI 的装配：不配任何东西就有 ChatClient.Builder、ChatModel、EmbeddingModel，注入的 Builder 直接能用。
 * 模型配置用假的，所以不需要数据库。
 */
class MugsunSpringAiConfigurationTest {

	private final AiModelBizService models = Mockito.mock(AiModelBizService.class);

	private ApplicationContextRunner runner() {
		return new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
			.withBean(AiModelBizService.class, () -> models)
			.withBean(AiLlmClient.class, FakeModelServer::llm)
			.withBean(ObjectMapper.class, ObjectMapper::new)
			.withUserConfiguration(AiSpringAiProperties.class, MugsunChatModel.class, MugsunEmbeddingModel.class,
				MugsunSpringAiConfiguration.class);
	}

	@Test
	@DisplayName("不配任何东西：三个 Bean 都在，超时 5 分钟，Builder 每次注入是新的一份，建出来的客户端直接能调模型")
	void worksWithoutConfiguration() throws Exception {
		FakeModelServer server = new FakeModelServer();
		try {
			Mockito.when(models.requireDefaultChat()).thenReturn(server.model("chat", "default-chat"));
			server.json("""
				{"id":"c1","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"你好"},
				 "finish_reason":"stop"}]}
				""");
			runner().run(ctx -> {
				assertThat(ctx).hasNotFailed();
				assertThat(ctx).hasSingleBean(ChatModel.class).hasSingleBean(EmbeddingModel.class);
				assertThat(ctx.getBean(AiSpringAiProperties.class).getTimeout()).isEqualTo(Duration.ofMinutes(5));

				ChatClient.Builder builder = ctx.getBean(ChatClient.Builder.class);
				assertThat(ctx.getBean(ChatClient.Builder.class)).as("各处拿到的不是同一份").isNotSameAs(builder);
				assertThat(builder.build().prompt().user("在吗").call().content()).isEqualTo("你好");
				assertThat(server.requests.get(0).path("model").asText()).isEqualTo("default-chat");
			});
		} finally {
			server.close();
		}
	}

	@Test
	@DisplayName("配了超时就用配的；应用自己提供了 ChatClient.Builder 时不再另建")
	void timeoutAndCustomBuilder() {
		ChatClient.Builder custom = ChatClient.builder(Mockito.mock(ChatModel.class));
		runner()
			.withPropertyValues("mugsun.ai.spring-ai.timeout=90s")
			.withBean("customChatClientBuilder", ChatClient.Builder.class, () -> custom)
			.run(ctx -> {
				assertThat(ctx).hasNotFailed();
				assertThat(ctx.getBean(AiSpringAiProperties.class).getTimeout()).isEqualTo(Duration.ofSeconds(90));
				assertThat(ctx.getBean(ChatClient.Builder.class)).isSameAs(custom);
			});
	}

	@Test
	@DisplayName("超时配成 0：启动失败，报错写出配置项和改法")
	void zeroTimeoutFailsStartup() {
		runner().withPropertyValues("mugsun.ai.spring-ai.timeout=0s").run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(ctx.getStartupFailure()).rootCause()
				.hasMessageContaining("mugsun.ai.spring-ai.timeout").hasMessageContaining("要大于 0");
		});
	}
}
