package com.mugsun.boot.ai.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.ai.support.AiOutboundProperties;
import com.mugsun.boot.ai.support.OutboundWebClientFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 测试用的假模型服务（OpenAI 兼容接口）：按顺序回放预先排好的响应，并记下收到的每个请求。
 * 请求经真实的 {@link AiLlmClient} 和出站校验发过来，只是把本机地址加进了白名单。
 */
final class FakeModelServer implements AutoCloseable {

	private static final ObjectMapper JSON = new ObjectMapper();

	private final HttpServer server;
	private final ExecutorService executor = Executors.newCachedThreadPool();
	private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();

	/** 收到的请求体，按先后 */
	final List<JsonNode> requests = new CopyOnWriteArrayList<>();

	/** 收到的 Authorization 头，按先后 */
	final List<String> authorizations = new CopyOnWriteArrayList<>();

	private record Reply(int status, String contentType, String body) {
	}

	FakeModelServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/v1/chat/completions", this::handle);
		server.createContext("/v1/embeddings", this::handle);
		server.setExecutor(executor);
		server.start();
	}

	/** 下一个请求回一段 JSON */
	FakeModelServer json(String body) {
		replies.add(new Reply(200, "application/json", body));
		return this;
	}

	/** 下一个请求回一串 SSE：每个参数是一条 data，结尾自动补 [DONE] */
	FakeModelServer sse(String... data) {
		StringBuilder body = new StringBuilder();
		for (String line : data) {
			body.append("data: ").append(line).append("\n\n");
		}
		body.append("data: [DONE]\n\n");
		replies.add(new Reply(200, "text/event-stream", body.toString()));
		return this;
	}

	/** 下一个请求回一个错误状态 */
	FakeModelServer status(int status, String body) {
		replies.add(new Reply(status, "application/json", body));
		return this;
	}

	String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
	}

	/** 指向这个假服务的模型配置 */
	AiModel model(String type, String code) {
		AiModel model = new AiModel();
		model.setTenantId("T1");
		model.setModelName(code + " 模型");
		model.setModelType(type);
		model.setModelCode(code);
		model.setBaseUrl(baseUrl());
		model.setApiKey("test-key");
		return model;
	}

	/** 真实的模型客户端，只放行本机地址 */
	static AiLlmClient llm() {
		AiOutboundProperties outbound = new AiOutboundProperties();
		outbound.setAllowHosts(List.of("127.0.0.1"));
		return new AiLlmClient(new OutboundWebClientFactory(WebClient.builder(), outbound), JSON);
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			requests.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
			authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
			Reply reply = replies.poll();
			if (reply == null) {
				reply = new Reply(500, "application/json", "{\"error\":{\"message\":\"测试没有排这次请求的响应\"}}");
			}
			byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", reply.contentType());
			exchange.sendResponseHeaders(reply.status(), body.length);
			exchange.getResponseBody().write(body);
		}
	}

	@Override
	public void close() {
		server.stop(0);
		executor.shutdownNow();
	}
}
