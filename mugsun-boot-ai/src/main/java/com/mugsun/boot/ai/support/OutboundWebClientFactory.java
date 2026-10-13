package com.mugsun.boot.ai.support;

import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * AI 模块所有「地址可由用户配置」的出站 WebClient（模型调用 / 知识库抓取 / 渠道 Webhook / MCP 远程 / 工作流 HTTP 节点）。
 * <ul>
 *   <li>每个请求发出前强制 {@link UrlSafetyGate#guard}（含 IP 字面量、端口）——新增调用点漏写前置校验也拦得住；</li>
 *   <li>建连解析走 {@link GuardedAddressResolverGroup}：解析后即校验、钉住校验过的 IP 连接（防 DNS rebinding）；</li>
 *   <li>禁止自动跟随重定向（3xx 原样返回）。</li>
 * </ul>
 * 内网地址只能经平台级配置 {@code mugsun.ai.outbound.allow-hosts} 放行（yaml，租户不可配）。
 */
@Component
public class OutboundWebClientFactory {

	private final WebClient.Builder builder;
	private final AiOutboundProperties properties;
	private volatile UrlSafetyGate.HostResolver resolver = UrlSafetyGate.SYSTEM_RESOLVER;

	public OutboundWebClientFactory(WebClient.Builder builder, AiOutboundProperties properties) {
		this.builder = builder;
		this.properties = properties;
	}

	/** 测试注入假 DNS */
	void setResolver(UrlSafetyGate.HostResolver resolver) {
		this.resolver = resolver;
	}

	/** 按 mugsun.ai.outbound.allow-hosts 白名单放行内网主机 */
	public WebClient create() {
		return build(properties::getAllowHosts);
	}

	/** 不带白名单（工作流 HTTP 节点沿用原语义：一律禁内网） */
	public WebClient createStrict() {
		return build(List::of);
	}

	private WebClient build(Supplier<Collection<String>> allowHosts) {
		UrlSafetyGate.HostResolver dns = resolver;
		HttpClient http = HttpClient.create()
			.resolver(new GuardedAddressResolverGroup(allowHosts, dns))
			.followRedirect(false);
		ExchangeFilterFunction guardEach = ExchangeFilterFunction.ofRequestProcessor(request ->
			Mono.fromCallable(() -> {
				UrlSafetyGate.guard(request.url().toString(), allowHosts.get(), properties.getAllowedPorts(), dns);
				return request;
			}));
		return builder.clone()
			.clientConnector(new ReactorClientHttpConnector(http))
			.filter(guardEach)
			.build();
	}
}
