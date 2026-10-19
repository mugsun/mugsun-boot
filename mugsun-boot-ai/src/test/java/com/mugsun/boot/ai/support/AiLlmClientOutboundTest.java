package com.mugsun.boot.ai.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.core.tool.exception.ServiceException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** 模型调用（普通 + 流式）走统一外呼客户端：白名单内网模型可用；DNS rebinding、重定向都被拦。 */
class AiLlmClientOutboundTest {

	private HttpServer server;
	private int port;
	private final AtomicInteger hits = new AtomicInteger();
	private final AtomicInteger secretHits = new AtomicInteger();

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/v1/chat/completions", ex -> {
			hits.incrementAndGet();
			byte[] body = "{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}".getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().add("Content-Type", "application/json");
			ex.sendResponseHeaders(200, body.length);
			ex.getResponseBody().write(body);
			ex.close();
		});
		server.createContext("/redir/chat/completions", ex -> {
			hits.incrementAndGet();
			ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/secret");
			ex.sendResponseHeaders(307, -1);
			ex.close();
		});
		server.createContext("/secret", ex -> {
			secretHits.incrementAndGet();
			ex.sendResponseHeaders(200, -1);
			ex.close();
		});
		server.start();
		port = server.getAddress().getPort();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private AiLlmClient client(List<String> allow, UrlSafetyGate.HostResolver dns) {
		AiOutboundProperties props = new AiOutboundProperties();
		props.setAllowHosts(allow);
		props.setAllowedPorts(List.of(80, 443, port));
		OutboundWebClientFactory f = new OutboundWebClientFactory(WebClient.builder(), props);
		f.setResolver(dns);
		return new AiLlmClient(f, new ObjectMapper());
	}

	private AiModel model(String baseUrl) {
		AiModel m = new AiModel();
		m.setBaseUrl(baseUrl);
		m.setModelCode("m");
		m.setModelType("chat");
		return m;
	}

	private static InetAddress[] ip(String s) {
		try {
			return new InetAddress[]{InetAddress.getByName(s)};
		} catch (java.net.UnknownHostException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void allowlistedInternalModelWorks() {
		AiLlmClient c = client(List.of("ollama.test"), h -> ip("127.0.0.1"));
		assertEquals("pong", c.chat(model("http://ollama.test:" + port + "/v1"), List.of(Map.of("content", "hi")), 8));
	}

	@Test
	void internalModelWithoutAllowlistIsRefused() {
		AiLlmClient c = client(List.of(), h -> ip("127.0.0.1"));
		assertThrows(ServiceException.class,
			() -> c.chat(model("http://ollama.test:" + port + "/v1"), List.of(Map.of("content", "hi")), 8));
		assertThrows(ServiceException.class,
			() -> c.chat(model("http://169.254.169.254/latest"), List.of(Map.of("content", "hi")), 8));
		assertEquals(0, hits.get());
	}

	@Test
	void dnsRebindingRefusedForChatAndStream() {
		AtomicInteger calls = new AtomicInteger();
		UrlSafetyGate.HostResolver rebinding = h -> calls.getAndIncrement() % 2 == 0 ? ip("93.184.216.34") : ip("127.0.0.1");
		AiLlmClient c = client(List.of(), rebinding);
		AiModel m = model("http://rebind.test:" + port + "/v1");
		calls.set(0);
		assertThrows(ServiceException.class, () -> c.chat(m, List.of(Map.of("content", "hi")), 8));
		calls.set(0);
		AtomicReference<Throwable> err = new AtomicReference<>();
		c.stream(m, List.of(Map.of("content", "hi")), 8, d -> { }, err::set, () -> { });
		assertNotNull(err.get(), "流式调用也必须被拦");
		assertEquals(0, hits.get(), "回环上的模型服务不应收到请求");
	}

	@Test
	void redirectsNotFollowed() {
		AiLlmClient c = client(List.of("ollama.test"), h -> ip("127.0.0.1"));
		try {
			c.chat(model("http://ollama.test:" + port + "/redir"), List.of(Map.of("content", "hi")), 8);
		} catch (ServiceException ignored) {
			// 307 无正文，解析失败即可；关键是不跟随
		}
		assertEquals(1, hits.get());
		assertEquals(0, secretHits.get());
	}
}
