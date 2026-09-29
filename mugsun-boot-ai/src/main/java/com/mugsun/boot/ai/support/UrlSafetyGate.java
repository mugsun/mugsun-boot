package com.mugsun.boot.ai.support;

import com.mugsun.core.tool.exception.ServiceException;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * 工作流 HTTP 节点 URL 闸门：仅 http(s)，拒绝内网 / 链路本地 / 元数据地址，防 SSRF。
 */
public final class UrlSafetyGate {

	private static final Set<String> BLOCKED_HOSTS = Set.of(
		"localhost", "metadata.google.internal", "metadata.aws.internal");

	private UrlSafetyGate() {
	}

	public static String guard(String rawUrl) {
		if (rawUrl == null || rawUrl.isBlank()) {
			throw new ServiceException("HTTP 节点 URL 不能为空");
		}
		URI uri;
		try {
			uri = URI.create(rawUrl.trim());
		} catch (Exception e) {
			throw new ServiceException("HTTP 节点 URL 非法");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!"http".equals(scheme) && !"https".equals(scheme)) {
			throw new ServiceException("HTTP 节点仅允许 http/https");
		}
		String host = uri.getHost();
		if (host == null || host.isBlank()) {
			throw new ServiceException("HTTP 节点 URL 缺少主机");
		}
		String hostLower = host.toLowerCase(Locale.ROOT);
		if (BLOCKED_HOSTS.contains(hostLower) || hostLower.endsWith(".local") || hostLower.endsWith(".internal")) {
			throw new ServiceException("HTTP 节点禁止访问内网/元数据地址");
		}
		try {
			InetAddress addr = InetAddress.getByName(host);
			if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()
				|| addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
				throw new ServiceException("HTTP 节点禁止访问内网地址");
			}
			// 云元数据常见
			String ip = addr.getHostAddress();
			if ("169.254.169.254".equals(ip) || ip.startsWith("169.254.")) {
				throw new ServiceException("HTTP 节点禁止访问链路本地地址");
			}
		} catch (ServiceException e) {
			throw e;
		} catch (Exception e) {
			throw new ServiceException("HTTP 节点主机解析失败");
		}
		return uri.toString();
	}
}
