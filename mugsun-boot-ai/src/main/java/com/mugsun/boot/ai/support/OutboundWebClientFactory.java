package com.mugsun.boot.ai.support;

import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * 用户可配置地址的出站 WebClient（知识库抓取 / 渠道 Webhook / MCP 远程 / 工作流 HTTP 节点）。
 * <ul>
 *   <li>建连解析走 {@link GuardedAddressResolverGroup}：解析后即校验、钉住校验过的 IP 连接；</li>
 *   <li>禁止自动跟随重定向（3xx 原样返回），避免公网地址 302 到内网绕过校验。</li>
 * </ul>
 * 调用方仍应先用 {@link UrlSafetyGate#guard} 做前置校验（给出友好提示、拦 IP 字面量）。
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
		HttpClient http = HttpClient.create()
			.resolver(new GuardedAddressResolverGroup(allowHosts, resolver))
			.followRedirect(false);
		return builder.clone().clientConnector(new ReactorClientHttpConnector(http)).build();
	}
}
