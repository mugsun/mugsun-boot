package com.mugsun.boot.ai.support;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 本机起一个 HTTP 服务，用假 DNS 把各测试主机指到 127.0.0.1，验证钉 IP 与禁重定向。 */
class OutboundWebClientFactoryTest {

	private HttpServer server;
	private int port;
	private final AtomicInteger hits = new AtomicInteger();
	private final AtomicInteger secretHits = new AtomicInteger();

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/ok", ex -> {
			hits.incrementAndGet();
			byte[] body = "pong".getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, body.length);
			ex.getResponseBody().write(body);
			ex.close();
		});
		server.createContext("/redirect", ex -> {
			hits.incrementAndGet();
			ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/secret");
			ex.sendResponseHeaders(302, -1);
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

	OutboundWebClientFactory factory(List<String> allow, UrlSafetyGate.HostResolver resolver) {
		AiOutboundProperties props = new AiOutboundProperties();
		props.setAllowHosts(allow);
		// 测试服务在随机端口：放行该端口，确保被拦是因为地址而不是端口
		props.setAllowedPorts(List.of(80, 443, port));
		OutboundWebClientFactory f = new OutboundWebClientFactory(WebClient.builder(), props);
		f.setResolver(resolver);
		return f;
	}

	private static InetAddress[] ip(String s) throws UnknownHostException {
		return new InetAddress[]{InetAddress.getByName(s)};
	}

	@Test
	void allowlistedHostConnectsToPinnedAddress() {
		OutboundWebClientFactory f = factory(List.of("svc.test"), h -> ip("127.0.0.1"));
		String body = f.create().get().uri("http://svc.test:" + port + "/ok")
			.retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5));
		assertEquals("pong", body);
		assertEquals(1, hits.get());
	}

	@Test
	void dnsRebindingToLoopbackIsRefusedAtConnect() {
		// 每个请求：第一次解析（逐请求 guard）给公网地址，第二次解析（建连）换成回环
		AtomicInteger calls = new AtomicInteger();
		UrlSafetyGate.HostResolver rebinding = h -> calls.getAndIncrement() % 2 == 0 ? ip("93.184.216.34") : ip("127.0.0.1");
		String url = "http://rebind.test:" + port + "/ok";
		OutboundWebClientFactory f = factory(List.of(), rebinding);
		calls.set(0);
		assertThrows(Exception.class, () -> f.create().get().uri(url)
			.retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5)));
		assertTrue(calls.get() >= 2, "逐请求 guard 通过后，建连时又解析了一次并被拦");
		calls.set(0);
		assertThrows(Exception.class, () -> f.createStrict().get().uri(url)
			.retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5)));
		assertEquals(0, hits.get(), "回环服务不应收到任何请求");
	}

	@Test
	void mixedAnswerWithAnyPrivateAddressIsRefused() throws Exception {
		InetAddress[] mixed = {InetAddress.getByName("93.184.216.34"), InetAddress.getByName("127.0.0.1")};
		OutboundWebClientFactory f = factory(List.of(), h -> mixed);
		assertThrows(Exception.class, () -> f.create().get().uri("http://mixed.test:" + port + "/ok")
			.retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5)));
		assertEquals(0, hits.get());
	}

	@Test
	void strictClientIgnoresAllowlist() {
		OutboundWebClientFactory f = factory(List.of("svc.test"), h -> ip("127.0.0.1"));
		assertThrows(Exception.class, () -> f.createStrict().get().uri("http://svc.test:" + port + "/ok")
			.retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5)));
		assertEquals(0, hits.get());
	}

	@Test
	void redirectsAreNotFollowed() {
		OutboundWebClientFactory f = factory(List.of("svc.test"), h -> ip("127.0.0.1"));
		Integer status = f.create().get().uri("http://svc.test:" + port + "/redirect")
			.exchangeToMono(r -> r.releaseBody().thenReturn(r.statusCode().value()))
			.block(Duration.ofSeconds(5));
		assertEquals(302, status);
		assertEquals(0, secretHits.get(), "重定向目标不应被请求");
	}

	@Test
	void literalIpIsGuardedPerRequest() {
		// IP 字面量不经过解析器，只能靠逐请求 guard 拦住
		OutboundWebClientFactory f = factory(List.of(), h -> ip("93.184.216.34"));
		assertThrows(Exception.class, () -> f.create().get().uri("http://127.0.0.1:" + port + "/ok")
			.retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5)));
		assertEquals(0, hits.get());
	}

	@Test
	void nonStandardPortRejectedUnlessAllowlisted() {
		AiOutboundProperties props = new AiOutboundProperties();
		OutboundWebClientFactory f = new OutboundWebClientFactory(WebClient.builder(), props);
		f.setResolver(h -> ip("93.184.216.34"));
		assertThrows(Exception.class, () -> f.create().get().uri("http://public.test:6379/")
			.retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5)));
		props.setAllowHosts(List.of("svc.test"));
		f.setResolver(h -> ip("127.0.0.1"));
		assertEquals("pong", f.create().get().uri("http://svc.test:" + port + "/ok")
			.retrieve().bodyToMono(String.class).block(Duration.ofSeconds(5)), "白名单主机不限端口");
	}
}
