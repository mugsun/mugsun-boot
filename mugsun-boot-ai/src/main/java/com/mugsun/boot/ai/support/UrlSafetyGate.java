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
			// 白名单主机：IP 字面量直接判；域名能解析时也判（解析失败留给连接时，连接期 resolveSafe 同样会拦）
			rejectMetadata(hostLower, resolver);
			return uri.toString();
		}
		if (BLOCKED_HOSTS.contains(hostLower) || hostLower.endsWith(".local") || hostLower.endsWith(".internal")) {
			throw new ServiceException("出站地址禁止访问内网/元数据地址");
		}
		resolveSafe(hostLower, allowHosts, resolver);
		return uri.toString();
	}

	/**
	 * 在 {@link #guard(String, Collection, HostResolver)} 基础上再限制端口：白名单主机不限端口；
	 * 其它主机只允许 allowedPorts（空表示不限）。缺省端口按 scheme 取 80/443。
	 */
	public static String guard(String rawUrl, Collection<String> allowHosts, Collection<Integer> allowedPorts,
							   HostResolver resolver) {
		String normalized = guard(rawUrl, allowHosts, resolver);
		URI uri = URI.create(normalized);
		if (allowedPorts == null || allowedPorts.isEmpty() || isAllowed(uri.getHost(), allowHosts)) {
			return normalized;
		}
		int port = uri.getPort();
		if (port < 0) {
			port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
		}
		if (!allowedPorts.contains(port)) {
			throw new ServiceException("出站地址端口不在允许范围内");
		}
		return normalized;
	}

	/**
	 * 解析主机并逐个校验地址，返回<b>已校验</b>的地址供连接直接使用（钉 IP，防 DNS rebinding：
	 * 校验与连接之间不再二次解析）。白名单主机跳过内网拦截；任一地址落在禁止网段即整体拒绝。
	 */
	static boolean isIpLiteral(String host) {
		return host.indexOf(':') >= 0 || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
	}

	public static InetAddress[] resolveSafe(String host, Collection<String> allowHosts, HostResolver resolver) {
		if (host == null || host.isBlank()) {
			throw new ServiceException("出站 URL 缺少主机");
		}
		String hostLower = stripBrackets(host.trim().toLowerCase(Locale.ROOT));
		boolean allowed = isAllowed(hostLower, allowHosts);
		if (!allowed && (BLOCKED_HOSTS.contains(hostLower) || hostLower.endsWith(".local")
			|| hostLower.endsWith(".internal") || hostLower.endsWith(".localhost"))) {
			throw new ServiceException("出站地址禁止访问内网/元数据地址");
		}
		InetAddress[] addrs;
		try {
			// IP 字面量直接按字面解析，不交给（可能被替换的）解析器
			addrs = isIpLiteral(hostLower) ? new InetAddress[]{InetAddress.getByName(hostLower)} : resolver.resolve(hostLower);
		} catch (Exception e) {
			throw new ServiceException("出站地址主机解析失败");
		}
		if (addrs == null || addrs.length == 0) {
			throw new ServiceException("出站地址主机解析失败");
		}
		for (InetAddress addr : addrs) {
			if (addr == null || isCloudMetadata(addr)) {
				throw new ServiceException("出站地址禁止访问云元数据地址");
			}
		}
		if (!allowed) {
			for (InetAddress addr : addrs) {
				if (addr == null || isPrivate(addr)) {
					throw new ServiceException("出站地址禁止访问内网地址");
				}
			}
		}
		return addrs.clone();
	}

	/** 云元数据地址（AWS/GCP/Azure 169.254.169.254、阿里云 100.100.100.200、AWS IPv6 fd00:ec2::254），含 IPv4 映射形式；白名单也不放行 */
	static boolean isCloudMetadata(InetAddress addr) {
		byte[] b = addr.getAddress();
		if (b.length == 16) {
			boolean mapped = true;
			for (int i = 0; i < 10; i++) {
				mapped &= b[i] == 0;
			}
			mapped &= (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
			if (mapped) {
				b = new byte[]{b[12], b[13], b[14], b[15]};
			} else {
				return java.util.Arrays.equals(b, METADATA_V6);
			}
		}
		int b0 = b[0] & 0xff, b1 = b[1] & 0xff, b2 = b[2] & 0xff, b3 = b[3] & 0xff;
		return (b0 == 169 && b1 == 254 && b2 == 169 && b3 == 254) || (b0 == 100 && b1 == 100 && b2 == 100 && b3 == 200);
	}

	private static void rejectMetadata(String hostLower, HostResolver resolver) {
		InetAddress[] addrs;
		try {
			addrs = isIpLiteral(hostLower) ? new InetAddress[]{InetAddress.getByName(hostLower)} : resolver.resolve(hostLower);
		} catch (Exception e) {
			return;
		}
		if (addrs != null) {
			for (InetAddress addr : addrs) {
				if (addr != null && isCloudMetadata(addr)) {
					throw new ServiceException("出站地址禁止访问云元数据地址");
				}
			}
		}
	}

	private static final byte[] METADATA_V6 = metadataV6();

	private static byte[] metadataV6() {
		try {
			return InetAddress.getByName("fd00:ec2::254").getAddress();
		} catch (java.net.UnknownHostException e) {
			throw new IllegalStateException(e);
		}
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
			return isPrivateV4(b, 0);
		}
		if (b.length == 16) {
			// fc00::/7 ULA（含 AWS fd00:ec2::254 元数据）
			if ((b[0] & 0xfe) == 0xfc) {
				return true;
			}
			// fe80::/10 链路本地、fec0::/10 旧站点本地（isLinkLocal/isSiteLocal 已覆盖，显式兜底）
			if ((b[0] & 0xff) == 0xfe && (b[1] & 0xc0) != 0) {
				return true;
			}
			// ::/96（含 ::、::1 与 IPv4-compatible ::a.b.c.d）
			if (allZero(b, 0, 12)) {
				return true;
			}
			// ::ffff:a.b.c.d IPv4-mapped：按内嵌 IPv4 判定
			if (allZero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
				return isPrivateV4(b, 12);
			}
			// 64:ff9b::/96 NAT64：按内嵌 IPv4 判定
			if ((b[0] & 0xff) == 0x00 && (b[1] & 0xff) == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b
				&& allZero(b, 4, 12)) {
				return isPrivateV4(b, 12);
			}
			// 2002::/16 6to4：按内嵌 IPv4 判定
			if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02) {
				return isPrivateV4(b, 2);
			}
			// 2001:db8::/32 文档地址、2001::/32 Teredo（内嵌 IPv4 取反存储，整段禁止）
			if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x01
				&& (((b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) || (b[2] == 0 && b[3] == 0))) {
				return true;
			}
			return false;
		}
		return true;
	}

	/** IPv4 禁止网段（b[off..off+3]）：私网/回环/链路本地/CGNAT/保留/基准测试/广播等 */
	private static boolean isPrivateV4(byte[] b, int off) {
		int b0 = b[off] & 0xff, b1 = b[off + 1] & 0xff, b2 = b[off + 2] & 0xff;
		return b0 == 0 // 0.0.0.0/8
			|| b0 == 10 // 10/8
			|| b0 == 127 // 回环
			|| (b0 == 100 && b1 >= 64 && b1 <= 127) // 100.64/10 CGNAT（含阿里云 100.100.100.200 元数据）
			|| (b0 == 169 && b1 == 254) // 链路本地（含 169.254.169.254 元数据）
			|| (b0 == 172 && b1 >= 16 && b1 <= 31)
			|| (b0 == 192 && b1 == 168)
			|| (b0 == 192 && b1 == 0 && b2 == 0) // 192.0.0.0/24 IETF 协议分配
			|| (b0 == 192 && b1 == 0 && b2 == 2) // 192.0.2.0/24 文档地址
			|| (b0 == 192 && b1 == 88 && b2 == 99) // 192.88.99.0/24 6to4 中继
			|| (b0 == 198 && b1 == 51 && b2 == 100) // 198.51.100.0/24 文档地址
			|| (b0 == 203 && b1 == 0 && b2 == 113) // 203.0.113.0/24 文档地址
			|| (b0 == 198 && (b1 == 18 || b1 == 19)) // 198.18/15 基准测试
			|| b0 >= 224; // 组播 + 240/4 保留 + 广播
	}

	private static boolean allZero(byte[] b, int from, int to) {
		for (int i = from; i < to; i++) {
			if (b[i] != 0) {
				return false;
			}
		}
		return true;
	}

	private static String stripBrackets(String h) {
		return h.startsWith("[") && h.endsWith("]") ? h.substring(1, h.length() - 1) : h;
	}
}
