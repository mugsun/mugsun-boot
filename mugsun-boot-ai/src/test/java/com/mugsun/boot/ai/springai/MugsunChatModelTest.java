package com.mugsun.boot.ai.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.mugsun.boot.ai.service.AiModelBizService;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Spring AI 对话模型：请求经真实的模型客户端发到本机的假模型服务，核对发出去的内容和读回来的结果。
 */
class MugsunChatModelTest {

	private static final Duration WAIT = Duration.ofSeconds(30);

	private FakeModelServer server;
	private AiModelBizService models;
	private MugsunChatModel chatModel;

	/** 每次查默认模型时，当时的租户和线程 */
	private final List<String> lookupTenants = new CopyOnWriteArrayList<>();
	private final List<String> lookupThreads = new CopyOnWriteArrayList<>();

	@BeforeEach
	void setUp() throws Exception {
		server = new FakeModelServer();
		models = Mockito.mock(AiModelBizService.class);
		Mockito.when(models.requireDefaultChat()).thenAnswer(invocation -> {
			lookupTenants.add(String.valueOf(TenantContext.current()));
			lookupThreads.add(Thread.currentThread().getName());
			return server.model("chat", "default-chat");
		});
		chatModel = chatModel(ObservationRegistry.NOOP);
	}

	@AfterEach
	void tearDown() {
		TenantContext.clear();
		server.close();
	}

	private MugsunChatModel chatModel(ObservationRegistry registry) {
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		beans.addBean("observationRegistry", registry);
		return new MugsunChatModel(models, FakeModelServer.llm(), new ObjectMapper(), new AiSpringAiProperties(),
			beans.getBeanProvider(ObservationRegistry.class));
	}

	private static String answer(String content) {
		return """
			{"id":"chat-1","model":"default-chat-0711",
			 "choices":[{"index":0,"message":{"role":"assistant","content":%s},"finish_reason":"stop"}],
			 "usage":{"prompt_tokens":3,"completion_tokens":5,"total_tokens":8}}
			""".formatted(new TextNode(content));
	}

	/** 测试用的工具：记下被调用时的参数、租户、线程 */
	public static class WeatherTools {

		final List<String> cities = new CopyOnWriteArrayList<>();
		final List<String> tenants = new CopyOnWriteArrayList<>();
		final List<String> threads = new CopyOnWriteArrayList<>();

		@Tool(description = "查一个城市今天的天气")
		public String weather(@ToolParam(description = "城市名") String city) {
			cities.add(city);
			tenants.add(String.valueOf(TenantContext.current()));
			threads.add(Thread.currentThread().getName());
			return city + "今天晴，26 度";
		}
	}

	/** 测试用的工具：操作人不由模型填，由调用方放进工具上下文 */
	public static class OrderTools {

		final List<String> operators = new CopyOnWriteArrayList<>();

		@Tool(description = "取消一个订单")
		public String cancel(@ToolParam(description = "订单号") String orderNo, ToolContext context) {
			operators.add(String.valueOf(context.getContext().get("userId")));
			return "已取消 " + orderNo;
		}
	}

	public record Person(String name, int age) {
	}

	@Test
	@DisplayName("普通对话：按 OpenAI 兼容格式发请求，读出回答、结束原因和用量")
	void callSendsRequestAndReadsAnswer() {
		server.json(answer("你好，我在"));
		Prompt prompt = new Prompt(List.of(new SystemMessage("你是助手"), new UserMessage("你好")),
			ChatOptions.builder().temperature(0.2).maxTokens(64).stopSequences(List.of("。")).build());

		ChatResponse response = chatModel.call(prompt);

		assertThat(response.getResult().getOutput().getText()).isEqualTo("你好，我在");
		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
		assertThat(response.getMetadata().getId()).isEqualTo("chat-1");
		assertThat(response.getMetadata().getModel()).isEqualTo("default-chat-0711");
		assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(3);
		assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(5);
		assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(8);

		JsonNode request = server.requests.get(0);
		assertThat(request.path("model").asText()).isEqualTo("default-chat");
		assertThat(request.path("stream").asBoolean(true)).isFalse();
		assertThat(request.path("temperature").asDouble()).isEqualTo(0.2);
		assertThat(request.path("max_tokens").asInt()).isEqualTo(64);
		assertThat(request.at("/stop/0").asText()).isEqualTo("。");
		assertThat(request.path("messages").size()).isEqualTo(2);
		assertThat(request.at("/messages/0/role").asText()).isEqualTo("system");
		assertThat(request.at("/messages/0/content").asText()).isEqualTo("你是助手");
		assertThat(request.at("/messages/1/role").asText()).isEqualTo("user");
		assertThat(request.at("/messages/1/content").asText()).isEqualTo("你好");
		assertThat(request.has("tools")).isFalse();
		assertThat(request.has("top_p")).as("没设的选项不发").isFalse();
		assertThat(server.authorizations).containsExactly("Bearer test-key");
	}

	@Test
	@DisplayName("选项里写了模型名称：按名称找已激活的模型，不用默认模型")
	void modelPickedByName() {
		Mockito.when(models.requireActiveByName("chat", "快的那个")).thenReturn(server.model("chat", "fast-1"));
		server.json(answer("好"));

		chatModel.call(new Prompt("你好", ChatOptions.builder().model("快的那个").build()));

		assertThat(server.requests.get(0).path("model").asText()).isEqualTo("fast-1");
		Mockito.verify(models, Mockito.never()).requireDefaultChat();
	}

	@Test
	@DisplayName("ChatClient 工具调用：工具说明发给模型，模型要调的工具被执行，结果回传后拿到最终回答")
	void chatClientRunsTools() {
		server.json("""
			{"id":"chat-1","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":null,
			 "tool_calls":[{"id":"call_1","type":"function","function":{"name":"weather","arguments":"{\\"city\\":\\"杭州\\"}"}}]},
			 "finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14}}
			""");
		server.json("""
			{"id":"chat-2","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"杭州今天晴，适合出门"},
			 "finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":6,"total_tokens":26}}
			""");
		WeatherTools tools = new WeatherTools();

		ChatResponse response = ChatClient.create(chatModel).prompt()
			.user("杭州今天天气怎么样").tools(tools).call().chatResponse();

		assertThat(response.getResult().getOutput().getText()).isEqualTo("杭州今天晴，适合出门");
		assertThat(tools.cities).containsExactly("杭州");
		assertThat(response.getMetadata().getUsage().getTotalTokens()).as("两轮的用量加在一起").isEqualTo(40);

		JsonNode first = server.requests.get(0);
		assertThat(first.at("/tools/0/type").asText()).isEqualTo("function");
		assertThat(first.at("/tools/0/function/name").asText()).isEqualTo("weather");
		assertThat(first.at("/tools/0/function/description").asText()).isEqualTo("查一个城市今天的天气");
		assertThat(first.at("/tools/0/function/parameters/properties/city/type").asText()).isEqualTo("string");

		JsonNode second = server.requests.get(1);
		assertThat(second.at("/messages/0/role").asText()).isEqualTo("user");
		assertThat(second.at("/messages/1/role").asText()).isEqualTo("assistant");
		assertThat(second.at("/messages/1/tool_calls/0/id").asText()).isEqualTo("call_1");
		assertThat(second.at("/messages/1/tool_calls/0/type").asText()).isEqualTo("function");
		assertThat(second.at("/messages/1/tool_calls/0/function/name").asText()).isEqualTo("weather");
		assertThat(second.at("/messages/1/tool_calls/0/function/arguments").asText()).isEqualTo("{\"city\":\"杭州\"}");
		assertThat(second.at("/messages/2/role").asText()).isEqualTo("tool");
		assertThat(second.at("/messages/2/tool_call_id").asText()).isEqualTo("call_1");
		assertThat(second.at("/messages/2/content").asText()).contains("26 度");
		assertThat(second.at("/tools/0/function/name").asText()).as("第二轮仍带着工具说明").isEqualTo("weather");
	}

	@Test
	@DisplayName("结构化结果：回答按类解析，格式要求随问题一起发给模型")
	void entityIsParsedFromAnswer() {
		server.json(answer("{\"name\":\"张三\",\"age\":30}"));

		Person person = ChatClient.create(chatModel).prompt().user("介绍一下张三").call().entity(Person.class);

		assertThat(person).isEqualTo(new Person("张三", 30));
		assertThat(server.requests.get(0).at("/messages/0/content").asText()).contains("介绍一下张三").contains("JSON");
	}

	@Test
	@DisplayName("ChatClient 的常用写法：系统提示词、按名称选模型、调参数、给工具带上下文")
	void chatClientOptionsAndToolContext() {
		Mockito.when(models.requireActiveByName("chat", "快的那个")).thenAnswer(invocation -> {
			lookupTenants.add(String.valueOf(TenantContext.current()));
			return server.model("chat", "fast-1");
		});
		server.json("""
			{"id":"chat-1","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":null,
			 "tool_calls":[{"id":"call_1","type":"function","function":{"name":"cancel","arguments":"{\\"orderNo\\":\\"A1\\"}"}}]},
			 "finish_reason":"tool_calls"}]}
			""");
		server.json(answer("订单 A1 已取消"));
		OrderTools tools = new OrderTools();

		TenantContext.set("T1");
		String content = ChatClient.create(chatModel).prompt()
			.system("你是客服助手")
			.user("帮我取消订单 A1")
			.options(ChatOptions.builder().model("快的那个").temperature(0.2))
			.tools(tools)
			.toolContext(Map.of("userId", "u-1"))
			.call().content();

		assertThat(content).isEqualTo("订单 A1 已取消");
		assertThat(tools.operators).as("工具上下文里的值原样交给工具").containsExactly("u-1");
		assertThat(lookupTenants).as("两轮都按名称找模型，都在发起请求的租户里").containsExactly("T1", "T1");
		Mockito.verify(models, Mockito.never()).requireDefaultChat();

		JsonNode first = server.requests.get(0);
		assertThat(first.path("model").asText()).isEqualTo("fast-1");
		assertThat(first.path("temperature").asDouble()).isEqualTo(0.2);
		assertThat(first.at("/messages/0/role").asText()).isEqualTo("system");
		assertThat(first.at("/messages/0/content").asText()).isEqualTo("你是客服助手");
		assertThat(first.at("/messages/1/content").asText()).isEqualTo("帮我取消订单 A1");
		JsonNode parameters = first.at("/tools/0/function/parameters/properties");
		assertThat(parameters.has("orderNo")).isTrue();
		assertThat(parameters.size()).as("工具上下文不是要模型填的参数").isEqualTo(1);
		assertThat(server.requests.get(1).path("model").asText()).as("第二轮还是这个模型").isEqualTo("fast-1");
	}

	@Test
	@DisplayName("流式：文字来一段发一段，思考过程、结束原因、用量都带得出来")
	void streamEmitsChunks() {
		server.sse(
			"{\"id\":\"s1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"}}]}",
			"{\"id\":\"s1\",\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"先想一下\"}}]}",
			"{\"id\":\"s1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你\"}}]}",
			"{\"id\":\"s1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"好呀\"}}]}",
			"{\"id\":\"s1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
			"{\"id\":\"s1\",\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}");

		List<ChatResponse> chunks = chatModel.stream(new Prompt("你好")).collectList().block(WAIT);

		assertThat(chunks).as("第一条只有角色、没有内容，不单独发一片").hasSize(5);
		assertThat(chunks.stream().map(c -> c.getResult().getOutput().getText()).collect(Collectors.joining()))
			.isEqualTo("你好呀");
		assertThat(chunks.get(0).getResult().getOutput().getMetadata())
			.containsEntry(MugsunChatModel.REASONING_DELTA, "先想一下");
		assertThat(chunks.get(2).getResult().getOutput().getMetadata())
			.containsEntry(MugsunChatModel.REASONING_CONTENT, "先想一下")
			.containsEntry(MugsunChatModel.REASONING_DELTA, "");
		assertThat(chunks.get(3).getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
		assertThat(chunks.get(4).getMetadata().getUsage().getTotalTokens()).isEqualTo(5);
		assertThat(chunks.get(4).getMetadata().getId()).isEqualTo("s1");
		assertThat(server.requests.get(0).path("stream").asBoolean()).isTrue();
		assertThat(server.requests.get(0).path("model").asText()).isEqualTo("default-chat");
	}

	@Test
	@DisplayName("流式工具调用：分段到的参数拼完整；工具和下一轮在别的线程执行，仍按发起请求的租户")
	void streamRunsToolsInCallerTenant() {
		server.sse(
			"{\"id\":\"s1\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_9\",\"type\":\"function\",\"function\":{\"name\":\"weather\",\"arguments\":\"\"}}]}}]}",
			"{\"id\":\"s1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"city\\\":\"}}]}}]}",
			"{\"id\":\"s1\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"杭州\\\"}\"}}]}}]}",
			"{\"id\":\"s1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
		server.sse(
			"{\"id\":\"s2\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"杭州今天晴\"}}]}",
			"{\"id\":\"s2\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"，适合出门\"}}]}",
			"{\"id\":\"s2\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}");
		WeatherTools tools = new WeatherTools();
		String caller = Thread.currentThread().getName();

		TenantContext.set("T1");
		List<String> pieces = ChatClient.create(chatModel).prompt()
			.user("杭州今天天气怎么样").tools(tools).stream().content().collectList().block(WAIT);

		assertThat(String.join("", pieces)).isEqualTo("杭州今天晴，适合出门");
		assertThat(tools.cities).containsExactly("杭州");
		assertThat(tools.threads).as("流式里工具不在发起请求的线程上执行").doesNotContain(caller);
		assertThat(tools.tenants).as("工具按发起请求的租户执行").containsExactly("T1");
		assertThat(lookupTenants).as("两轮查模型都按发起请求的租户").containsExactly("T1", "T1");
		assertThat(lookupThreads.get(0)).isEqualTo(caller);
		assertThat(lookupThreads.get(1)).as("工具之后的那一轮不在发起请求的线程上").isNotEqualTo(caller);

		JsonNode second = server.requests.get(1);
		assertThat(second.at("/messages/1/tool_calls/0/id").asText()).isEqualTo("call_9");
		assertThat(second.at("/messages/1/tool_calls/0/function/name").asText()).isEqualTo("weather");
		assertThat(second.at("/messages/1/tool_calls/0/function/arguments").asText()).isEqualTo("{\"city\":\"杭州\"}");
		assertThat(second.at("/messages/2/tool_call_id").asText()).isEqualTo("call_9");
		assertThat(second.at("/messages/2/content").asText()).contains("26 度");
	}

	@Test
	@DisplayName("没有租户的线程上不会沿用别的请求的租户：选项里没记租户时，查模型就是没有租户")
	void noTenantIsNotInvented() {
		server.json(answer("好"));

		chatModel.call(new Prompt("你好", chatModel.getOptions()));

		assertThat(lookupTenants).containsExactly("null");
	}

	@Test
	@DisplayName("模型服务报错：普通和流式都报「模型调用失败」，类型和平台自己的对话一致")
	void failuresAreReportedAsCallFailed() {
		server.status(401, "{\"error\":{\"message\":\"bad key\"}}");
		assertThatThrownBy(() -> chatModel.call(new Prompt("你好")))
			.isInstanceOf(AiLlmClient.CallFailedException.class).hasMessageStartingWith("模型调用失败");

		server.status(401, "{\"error\":{\"message\":\"bad key\"}}");
		assertThatThrownBy(() -> chatModel.stream(new Prompt("你好")).collectList().block(WAIT))
			.isInstanceOf(AiLlmClient.CallFailedException.class).hasMessageStartingWith("模型调用失败");
	}

	@Test
	@DisplayName("服务返回 200 但响应体是错误：同样报失败，带上对方的原话")
	void errorPayloadIsAFailure() {
		server.json("{\"error\":{\"message\":\"quota exceeded\"}}");
		assertThatThrownBy(() -> chatModel.call(new Prompt("你好")))
			.isInstanceOf(AiLlmClient.CallFailedException.class).hasMessageContaining("quota exceeded");

		server.sse("{\"error\":{\"message\":\"quota exceeded\"}}");
		assertThatThrownBy(() -> chatModel.stream(new Prompt("你好")).collectList().block(WAIT))
			.isInstanceOf(AiLlmClient.CallFailedException.class).hasMessageContaining("quota exceeded");
	}

	@Test
	@DisplayName("图片输入：图片内容写成 data 地址和文字一起发；不是图片的附件直接拒绝，不发请求")
	void imagesAreSentAndOtherMediaRejected() {
		server.json(answer("一只猫"));
		UserMessage withImage = UserMessage.builder().text("图里是什么")
			.media(List.of(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[] {1, 2, 3})))).build();

		chatModel.call(new Prompt(withImage));

		JsonNode content = server.requests.get(0).at("/messages/0/content");
		assertThat(content.at("/0/type").asText()).isEqualTo("text");
		assertThat(content.at("/0/text").asText()).isEqualTo("图里是什么");
		assertThat(content.at("/1/type").asText()).isEqualTo("image_url");
		assertThat(content.at("/1/image_url/url").asText()).isEqualTo("data:image/png;base64,AQID");

		UserMessage withAudio = UserMessage.builder().text("听一下")
			.media(List.of(new Media(MimeTypeUtils.parseMimeType("audio/mpeg"), new ByteArrayResource(new byte[] {1}))))
			.build();
		assertThatThrownBy(() -> chatModel.call(new Prompt(withAudio)))
			.isInstanceOf(ServiceException.class).hasMessageContaining("只支持图片");
		assertThat(server.requests).hasSize(1);
	}

	@Test
	@DisplayName("观测：普通和流式各记一条，带实际用的模型标识和用量")
	void observationsAreRecorded() {
		List<Observation.Context> stopped = new CopyOnWriteArrayList<>();
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
			@Override
			public boolean supportsContext(Observation.Context context) {
				return true;
			}

			@Override
			public void onStop(Observation.Context context) {
				stopped.add(context);
			}
		});
		MugsunChatModel observed = chatModel(registry);

		server.json(answer("好"));
		observed.call(new Prompt("你好"));
		server.sse(
			"{\"id\":\"s1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"}}]}",
			"{\"id\":\"s1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"呀\"}}]}",
			"{\"id\":\"s1\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}",
			"{\"id\":\"s1\",\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}");
		observed.stream(new Prompt("你好")).collectList().block(WAIT);

		// 流式的那条是在另一个线程上收尾的，比调用方拿到最后一片晚一点
		await().atMost(WAIT).untilAsserted(() -> assertThat(stopped).hasSize(2));
		ChatModelObservationContext call = (ChatModelObservationContext) stopped.get(0);
		assertThat(call.getOperationMetadata().provider()).isEqualTo("mugsun");
		assertThat(call.isStreaming()).isFalse();
		assertThat(call.getRequest().getOptions().getModel()).isEqualTo("default-chat");
		assertThat(call.getResponse().getMetadata().getUsage().getTotalTokens()).isEqualTo(8);
		ChatModelObservationContext stream = (ChatModelObservationContext) stopped.get(1);
		assertThat(stream.isStreaming()).isTrue();
		assertThat(stream.getResponse().getResult().getOutput().getText()).as("流式记的是拼好的全文").isEqualTo("你好呀");
		assertThat(stream.getResponse().getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
		assertThat(stream.getResponse().getMetadata().getUsage().getTotalTokens()).as("用量在最后一片里").isEqualTo(5);
	}
}
