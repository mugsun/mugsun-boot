package com.mugsun.boot.ai.support;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 出站请求（知识库抓取 / 渠道 Webhook / MCP 远程）安全配置。
 * {@code mugsun.ai.outbound.allow-hosts}：白名单主机，支持精确主机与 {@code *.example.com} 通配；
 * 命中白名单的主机跳过内网地址拦截（如本地 Ollama、内网 MCP）。默认空 = 全部走 SSRF 校验。
 */
@Component
@ConfigurationProperties(prefix = "mugsun.ai.outbound")
public class AiOutboundProperties {

	private List<String> allowHosts = new ArrayList<>();

	public List<String> getAllowHosts() {
		return allowHosts;
	}

	public void setAllowHosts(List<String> allowHosts) {
		this.allowHosts = allowHosts == null ? new ArrayList<>() : allowHosts;
	}

	/** 校验并返回规范化 URL；不合规抛 ServiceException。 */
	public String guard(String url) {
		return UrlSafetyGate.guard(url, allowHosts, UrlSafetyGate.SYSTEM_RESOLVER);
	}
}
