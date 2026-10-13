package com.mugsun.boot.ai.support;

import com.mugsun.core.tool.exception.ServiceException;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 出站 URL 闸门：仅 http(s)，拒绝内网 / 回环 / 链路本地 / 元数据地址，防 SSRF。
 * 工作流 HTTP 节点、知识库抓取、渠道 Webhook、MCP 远程地址共用。
 * 命中白名单（精确主机或 {@code *.example.com} 通配）的主机可放行内网地址（如本地 Ollama）。
 */
public final class UrlSafetyGate {

	/** 主机解析器，测试可注入，避免真实 DNS。 */
	@FunctionalInterface
	public interface HostResolver {
		InetAddress[] resolve(String host) throws UnknownHostException;
	}

	public static final HostResolver SYSTEM_RESOLVER = InetAddress::getAllByName;

	private static final Set<String> BLOCKED_HOSTS = Set.of(
		"localhost", "metadata.google.internal", "metadata.aws.internal");

	private UrlSafetyGate() {
	}

	/** 无白名单、系统 DNS（工作流 HTTP 节点沿用）。 */
	public static String guard(String rawUrl) {
		return guard(rawUrl, List.of(), SYSTEM_RESOLVER);
	}

	public static String guard(String rawUrl, Collection<String> allowHosts, HostResolver resolver) {
		if (rawUrl == null || rawUrl.isBlank()) {
			throw new ServiceException("出站 URL 不能为空");
		}
		URI uri;
		try {
			uri = URI.create(rawUrl.trim());
		} catch (Exception e) {
			throw new ServiceException("出站 URL 非法");
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!"http".equals(scheme) && !"https".equals(scheme)) {
			throw new ServiceException("出站地址仅允许 http/https");
		}
		String host = uri.getHost();
		if (host == null || host.isBlank()) {
			throw new ServiceException("出站 URL 缺少主机");
		}
		String hostLower = stripBrackets(host.toLowerCase(Locale.ROOT));
		if (isAllowed(hostLower, allowHosts)) {
			return uri.toString();
		}
		if (BLOCKED_HOSTS.contains(hostLower) || hostLower.endsWith(".local") || hostLower.endsWith(".internal")) {
			throw new ServiceException("出站地址禁止访问内网/元数据地址");
		}
		InetAddress[] addrs;
		try {
			addrs = resolver.resolve(hostLower);
		} catch (Exception e) {
			throw new ServiceException("出站地址主机解析失败");
		}
		if (addrs == null || addrs.length == 0) {
			throw new ServiceException("出站地址主机解析失败");
		}
		for (InetAddress addr : addrs) {
			if (isPrivate(addr)) {
				throw new ServiceException("出站地址禁止访问内网地址");
			}
		}
		return uri.toString();
	}

	/** 白名单匹配：精确主机（忽略大小写），或 {@code *.example.com} 匹配其任意子域（不含 example.com 本身）。 */
	public static boolean isAllowed(String host, Collection<String> allowHosts) {
		if (host == null || allowHosts == null || allowHosts.isEmpty()) {
			return false;
		}
		String h = stripBrackets(host.trim().toLowerCase(Locale.ROOT));
		for (String raw : allowHosts) {
			if (raw == null || raw.isBlank()) {
				continue;
			}
			String p = stripBrackets(raw.trim().toLowerCase(Locale.ROOT));
			if (p.startsWith("*.")) {
				String suffix = p.substring(1); // ".example.com"
				if (h.endsWith(suffix) && h.length() > suffix.length()) {
					return true;
				}
			} else if (p.equals(h)) {
				return true;
			}
		}
		return false;
	}

	static boolean isPrivate(InetAddress addr) {
		if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()
			|| addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
			return true;
		}
		byte[] b = addr.getAddress();
		if (b.length == 4) {
			int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
			// 0.0.0.0/8、100.64.0.0/10(CGNAT)、169.254.0.0/16
			return b0 == 0 || (b0 == 100 && b1 >= 64 && b1 <= 127) || (b0 == 169 && b1 == 254);
		}
		if (addr instanceof Inet6Address) {
			// fc00::/7 ULA
			return (b[0] & 0xfe) == 0xfc;
		}
		return false;
	}

	private static String stripBrackets(String h) {
		return h.startsWith("[") && h.endsWith("]") ? h.substring(1, h.length() - 1) : h;
	}
}
