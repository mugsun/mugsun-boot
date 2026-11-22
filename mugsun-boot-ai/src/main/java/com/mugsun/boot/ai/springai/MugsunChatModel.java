package com.mugsun.boot.ai.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.boot.ai.service.AiModelBizService;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.i18n.message.I18n;
import com.mugsun.core.tool.exception.ServiceException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.Locale;

/**
 * Spring AI 的对话模型，接的是「大模型配置」里配好的模型。
 * <p>用哪个模型：选项里写了 {@code model} 就按名称找（先按「模型名称」，再按「模型标识」），没写就用当前租户的
 * 默认对话模型。每次调用都现查，所以不同租户各用各的模型，页面上改了配置下一次调用就生效。
 * <p>请求怎么发：经 {@link AiLlmClient} 按 OpenAI 兼容格式发出，和平台自己的对话用同一个客户端，模型地址照样过出站校验。
 * 这里不引 Spring AI 各家模型的接入包，它们自己联网，绕过这层校验。
 * <p>工具调用：模型这边只管把工具说明发给模型、把模型要调的工具原样交回；执行工具、再问模型由 {@code ChatClient} 负责。
 */
@Component
public class MugsunChatModel implements ChatModel {

	/** 回答消息的附加信息里，模型的思考过程（到目前为止的全文）；模型不给思考过程时是空串 */
	public static final String REASONING_CONTENT = "reasoningContent";

	/** 流式分片的附加信息里，这一片新增的思考过程 */
	public static final String REASONING_DELTA = "reasoningDelta";

	/** 观测数据里的提供方名称 */
	static final String PROVIDER = "mugsun";

	/** 和 ObservationThreadLocalAccessor.KEY 同值；不直接引用那个类，它依赖的包不一定在 */
	private static final String OBSERVATION_KEY = "micrometer.observation";

	private static final ChatModelObservationConvention CONVENTION = new DefaultChatModelObservationConvention();

	private final AiModelBizService models;
	private final AiLlmClient llm;
	private final ObjectMapper json;
	private final AiSpringAiProperties properties;
	private final ObservationRegistry observations;

	public MugsunChatModel(AiModelBizService models, AiLlmClient llm, ObjectMapper json,
						   AiSpringAiProperties properties, ObjectProvider<ObservationRegistry> observations) {
		this.models = models;
		this.llm = llm;
		this.json = json;
		this.properties = properties;
		this.observations = observations.getIfUnique(() -> ObservationRegistry.NOOP);
	}

	/**
	 * 默认选项：不指定模型和参数，只记下当前租户。{@code ChatClient} 每次请求都从这里取一份再叠加自己的设置。
	 */
	@Override
	public ChatOptions getOptions() {
		return MugsunChatOptions.builder().tenantId(TenantContext.current()).build();
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		AiModel model = model(prompt.getOptions());
		ObjectNode body = OpenAiWire.request(json, prompt);
		Locale locale = I18n.locale();
		ChatModelObservationContext context = ChatModelObservationContext.builder()
			.prompt(observed(prompt, model))
			.provider(PROVIDER)
			.build();
		return ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
			.observation(null, CONVENTION, () -> context, observations)
			.observe(() -> {
				ChatResponse response = OpenAiWire.response(json,
					llm.complete(model, body, properties.getTimeout()), locale);
				context.setResponse(response);
				return response;
			});
	}

	/**
	 * 流式对话。用哪个模型在调用这个方法时就定下来（租户绑在线程上，订阅可能发生在别的线程）；请求在订阅时才发。
	 * 分片在可以阻塞的线程上交给下游，下游里查库、再调模型都可以。
	 */
	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		AiModel model = model(prompt.getOptions());
		ObjectNode body = OpenAiWire.request(json, prompt);
		Locale locale = I18n.locale();
		Prompt observed = observed(prompt, model);
		return Flux.deferContextual(view -> {
			ChatModelObservationContext context = ChatModelObservationContext.builder()
				.prompt(observed)
				.provider(PROVIDER)
				.streaming(true)
				.build();
			Observation observation = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
				.observation(null, CONVENTION, () -> context, observations);
			Observation parent = view.getOrDefault(OBSERVATION_KEY, null);
			observation.parentObservation(parent);
			// 先把上一层观测设为当前再开始，链路里这一段才会挂在上一层下面，而不是挂在当前线程碰巧开着的那一段下面
			try (Observation.Scope ignored = parent != null ? parent.openScope() : Observation.Scope.NOOP) {
				observation.start();
			}
			OpenAiWire.Stream stream = new OpenAiWire.Stream(json, locale);
			// 观测数据记的是拼好的整段回答。不用 Spring AI 的 MessageAggregator：它在流出错时会再打一条带堆栈的 ERROR 日志，
			// 而错误本来就会交给调用方处理
			return llm.streamData(model, body)
				.timeout(properties.getTimeout())
				.concatMapIterable(stream::accept)
				.concatWith(Flux.defer(() -> Flux.fromIterable(stream.finish())))
				.onErrorMap(e -> failed(e, locale))
				.doOnComplete(() -> context.setResponse(stream.aggregated()))
				.publishOn(Schedulers.boundedElastic())
				.doOnError(observation::error)
				.doFinally(signal -> observation.stop())
				.contextWrite(ctx -> ctx.put(OBSERVATION_KEY, observation));
		});
	}

	/** 当前线程没有租户时（流式里工具之后的那几轮），按选项里记的租户查 */
	private AiModel model(ChatOptions options) {
		String name = options == null ? null : options.getModel();
		String tenantId = options instanceof MugsunChatOptions mugsun ? mugsun.tenantId() : null;
		return TenantScope.run(tenantId, () -> StringUtils.hasText(name)
			? models.requireActiveByName(AiConstants.MODEL_TYPE_CHAT, name)
			: models.requireDefaultChat());
	}

	/** 观测数据里记实际用的模型标识：选项里可能没写模型，或者写的是页面上起的名称 */
	private static Prompt observed(Prompt prompt, AiModel model) {
		String code = StringUtils.hasText(model.getModelCode()) ? model.getModelCode() : model.getModelName();
		ChatOptions options = prompt.getOptions();
		ChatOptions.Builder<?> builder = options == null ? ChatOptions.builder() : options.mutate();
		return new Prompt(prompt.getInstructions(), builder.model(code).build());
	}

	private static Throwable failed(Throwable error, Locale locale) {
		if (error instanceof ServiceException) {
			return error;
		}
		return new AiLlmClient.CallFailedException(I18n.get(locale, "模型调用失败: {0}", error.getMessage()));
	}
}
