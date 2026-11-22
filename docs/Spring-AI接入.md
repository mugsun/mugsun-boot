# 用 Spring AI 调模型

AI 模块带了 Spring AI 的模型接口和对话客户端（`ChatClient`）。业务代码可以按 Spring AI 官方文档的写法调模型，
调的是「大模型配置」页面上配好的模型，不用另外配地址和密钥。

不用配置就能用：AI 模块开着（默认开），页面上有一个已激活的对话模型就行。

## 最简单的用法

```java
@Service
public class ReplyService {

	private final ChatClient chatClient;

	public ReplyService(ChatClient.Builder builder) {
		this.chatClient = builder.defaultSystem("你是客服助手，回答要简短").build();
	}

	public String reply(String question) {
		return chatClient.prompt().user(question).call().content();
	}
}
```

`ChatClient.Builder` 每注入一次是新的一份，各处加自己的默认提示词、工具，互不影响。建好的 `ChatClient` 可以存成字段一直用，
每次调用按当时的租户找模型。

## 用哪个模型

| 写法 | 用的模型 |
| --- | --- |
| 不指定 | 当前租户的默认对话模型；没设默认的，用最近添加的已激活对话模型 |
| `.options(ChatOptions.builder().model("名称"))` | 先按「模型名称」找，找不到再按「模型标识」找；只找已激活的 |

每次调用都现查：不同租户各用各的模型，页面上改了配置下一次调用就生效。找不到模型时抛 `ServiceException`，
提示去「大模型配置」里核对。

温度等参数也写在 `options` 里，写了的才发给模型：`temperature`、`topP`、`topK`、`maxTokens`、
`frequencyPenalty`、`presencePenalty`、`stopSequences`。

```java
String answer = chatClient.prompt()
	.user(question)
	.options(ChatOptions.builder().model("快的那个").temperature(0.2))
	.call()
	.content();
```

## 工具调用

```java
public class OrderTools {

	@Tool(description = "按订单号查订单状态")
	public String status(@ToolParam(description = "订单号") String orderNo) {
		return orderService.statusOf(orderNo);
	}
}

String answer = chatClient.prompt()
	.user("订单 A1 到哪了")
	.tools(new OrderTools())
	.call()
	.content();
```

模型要调工具时，`ChatClient` 执行工具、把结果交回模型，直到拿到最终回答。`call()` 返回的用量是各轮加在一起的。

工具里抛出的运行时异常默认不会抛给调用方：异常的那句话当成工具结果交给模型，由模型接着回答（Spring AI 的默认做法）。
也就是说，这句话会发给模型服务：异常信息里不要带不该外传的内容；拿不准的地方在工具里自己接住异常，返回一句给模型看的话。

## 结构化结果

```java
public record Person(String name, int age) {
}

Person person = chatClient.prompt().user("介绍一下张三").call().entity(Person.class);
```

## 流式

```java
Flux<String> pieces = chatClient.prompt().user(question).stream().content();
```

要思考过程、结束原因、用量时用 `stream().chatResponse()`，每一片是一个 `ChatResponse`：

| 要什么 | 怎么取 |
| --- | --- |
| 这一片的文字 | `chunk.getResult().getOutput().getText()` |
| 这一片新增的思考过程 | `chunk.getResult().getOutput().getMetadata().get(MugsunChatModel.REASONING_DELTA)` |
| 到目前为止的思考过程 | 同上，键是 `MugsunChatModel.REASONING_CONTENT`；`call()` 的结果也用这个键 |
| 结束原因 | `chunk.getResult().getMetadata().getFinishReason()`，大写：`STOP`、`TOOL_CALLS`、`LENGTH` |
| 用量 | `chunk.getMetadata().getUsage()`，在最后一片；模型服务没在流里给用量时是 0 |

模型不给思考过程时，那两个键的值是空串。

## 向量

```java
@Service
public class SimilarService {

	private final EmbeddingModel embeddingModel;

	public SimilarService(EmbeddingModel embeddingModel) {
		this.embeddingModel = embeddingModel;
	}

	public float[] vectorOf(String text) {
		return embeddingModel.embed(text);
	}
}
```

- 一次转多段：`embeddingModel.embed(List.of("第一段", "第二段"))`，结果和传入的顺序一致。
- 用的是当前租户的默认向量模型。要指定模型：
  `embeddingModel.call(new EmbeddingRequest(texts, EmbeddingOptions.builder().model("名称").build()))`，找法和对话模型一样。
- 维度以配置页上填的为准（`embeddingModel.dimensions()`；没填时发一次请求量出来），选项里的 `dimensions` 不会发给模型。
- 向量调用不返回用量。

## 租户、登录身份和线程

- `call()`：模型请求和工具都在调用方的线程上执行，租户、登录身份和平常一样。
- `stream()`：租户在调用 `.stream()` 的那一刻记下，请求在订阅时才发出。工具、工具之后再问模型的那几轮在别的线程上执行：
  - **租户带过去了**，工具里查租户表和平常一样；
  - **登录身份没带过去**，工具里 `StpUtil.getLoginId()` 取不到。工具要用到操作人时，在发起处取好，用 `toolContext` 传（见下）。
- 定时任务、消息消费这类没有登录会话的地方，先指定租户再调：
  `TenantContext.execute(tenantId, () -> chatClient.prompt().user(question).call().content())`。
- 平台管理员处在「查看全部租户」时没有具体的租户可带：`stream()` 加工具的写法会在工具执行后报没有租户的错误，
  同样先用 `TenantContext.execute` 指定一个租户。

给工具传操作人：

```java
String userId = StpUtil.getLoginIdAsString();
Flux<String> pieces = chatClient.prompt()
	.user(question)
	.tools(new OrderTools())
	.toolContext(Map.of("userId", userId))
	.stream()
	.content();

// 工具方法多写一个 ToolContext 参数，它不算要模型填的参数
@Tool(description = "取消一个订单")
public String cancel(@ToolParam(description = "订单号") String orderNo, ToolContext context) {
	String userId = (String) context.getContext().get("userId");
	return orderService.cancel(orderNo, userId);
}
```

操作人一定从 `toolContext` 取，不要让模型填：模型填的参数可能被用户的话带偏。

## 配置

只有一项，不配也能用：

```yaml
mugsun:
  ai:
    spring-ai:
      timeout: 5m   # 默认 5 分钟
```

- `call()` 指一次模型请求最多等多久（有工具时每一轮各算各的）；`stream()` 指前后两段内容之间最长等多久。
- 模型的地址、密钥在「大模型配置」页面上配。`spring.ai.*` 这套配置在本项目里不起作用（没有引 Spring AI 的自动装配包）。
- 请求和平台自己的对话走同一个出站客户端，模型地址照样过出站校验：内网地址的模型（比如本机的 Ollama）要在
  `mugsun.ai.outbound.allow-hosts` 里放行，和平台自己连内网模型的要求一样。
- AI 模块没装或者启动时关掉（`MUGSUN_AI_ENABLED=false`）时，`ChatClient.Builder`、`ChatModel`、`EmbeddingModel`
  这几个 Bean 不存在。别的模块要用又不想强依赖 AI 模块，注入 `ObjectProvider<ChatClient.Builder>`。
  运行时在系统参数里关掉 AI 模块后，Bean 还在，调用时抛 `ServiceException`。

## 只带了四个包

| 包 | 内容 |
| --- | --- |
| `spring-ai-model` | `ChatModel`、`EmbeddingModel`、消息、选项、工具调用、对话记忆（`ChatMemory`） |
| `spring-ai-client-chat` | `ChatClient` 和它自带的 Advisor（`MessageChatMemoryAdvisor`、`SimpleLoggerAdvisor` 等） |
| `spring-ai-commons`、`spring-ai-template-st` | 上面两个包依赖的公共包和提示词模板 |

其余的 Spring AI 包和 MCP SDK 在构建时被根 `pom.xml` 的 enforcer 规则拦下：

- 各家模型的接入包：它们自己联网，不过出站校验；
- 自动装配包和 starter：会按 `spring.ai.*` 再建一套模型，绕开「大模型配置」；
- MCP：内置 MCP Server 还没加鉴权，暂时下线；
- 向量库和 RAG：知识库检索用平台自己的知识库功能。

确实需要再加一个 Spring AI 包时，按新增依赖走安全评审，评审通过后三处一起改：根 `pom.xml` 里那条规则的 `includes`、
`SpringAiSurfaceGuardTest` 里的清单、`scripts/check-banned-dependencies.sh` 里的探针。

## 还要知道的

- 附件只支持图片：`UserMessage` 里带 `Media`，给地址的原样传给模型服务（由它去取），给内容的转成 data 地址。别的类型会报错。
  图片内容由你的代码读出来再传（比如用户上传的文件）；不要把用户给的地址包成 `UrlResource` 让程序自己去读，那样不过出站校验。
- 报错：模型服务不通、返回错误时抛 `AiLlmClient.CallFailedException`（「模型调用失败: …」），和平台自己的对话一样；
  提示语跟着请求的语言走。
- 观测：每次模型调用记一条 `gen_ai.client.operation`，提供方是 `mugsun`，带实际用的模型标识和用量。
- 用 `ChatClient` 发起的调用不会出现在平台的对话记录里；要记用量，自己从返回结果里取。
- 对话记忆等 Spring AI 自带的 Advisor 在放行的包里，可以照官方文档用，本仓没有针对它们的测试。
  自带的记忆存在内存里：重启就没了，多实例之间不共享，会话编号要自己按租户和用户区分开。
- 版本：Spring AI 2.0.x 对应 Spring Boot 4.1，版本号在根 `pom.xml` 的 `spring-ai.version`。
