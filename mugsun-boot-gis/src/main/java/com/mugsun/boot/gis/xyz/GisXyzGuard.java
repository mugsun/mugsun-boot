package com.mugsun.boot.gis.xyz;

import com.mugsun.core.tool.exception.ServiceException;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * XYZ 底图的 SSRF 防线（纯函数，便于单测）：
 * <ol>
 *   <li>只允许 https、默认端口、无 userinfo / fragment；</li>
 *   <li>主机名必须命中部署级白名单，IP 字面量一律拒绝；</li>
 *   <li>出站前解析 DNS，任一地址落在回环 / 内网 / 链路本地 / CGNAT / 组播 / 保留段即拒绝。</li>
 * </ol>
 */
public final class GisXyzGuard {

	public static final String MSG_TEMPLATE = "瓦片地址须为 https，且包含 {z}、{x}、{y}";
	public static final String MSG_HOST = "瓦片域名不在允许列表内。请联系运维在 mugsun.gis.xyz.allowed-hosts 中放行";
	public static final String MSG_IP = "瓦片地址不能直接写 IP，请使用白名单内的域名";
	public static final String MSG_SUBDOMAINS = "子域请填 1～8 个，用逗号分隔，每个只含字母或数字";
	public static final String MSG_ATTRIBUTION = "版权归属请控制在 200 个字以内";
	public static final String MSG_PRIVATE = "瓦片域名解析到了内网或保留地址，已拒绝";

	private static final int URL_MAX = 512;
	private static final Pattern SUBDOMAIN = Pattern.compile("^[a-z0-9]{1,16}$");
	private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
	private static final Pattern HOSTNAME = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$");

	/** DNS 解析钩子，单测里替换 */
	@FunctionalInterface
	public interface Resolver {
		InetAddress[] resolve(String host) throws UnknownHostException;
	}

	public static final Resolver SYSTEM_RESOLVER = InetAddress::getAllByName;

	private GisXyzGuard() {
	}

	/** 保存配置时校验整份模板：每个子域代入后的主机都要过白名单 */
	public static GisXyzTemplate checkTemplate(GisXyzTemplate t, List<String> allowedHosts) {
		if (t == null || t.url() == null) {
			throw new ServiceException(MSG_TEMPLATE);
		}
		String url = t.url().trim();
		if (url.length() > URL_MAX || !url.startsWith("https://")
			|| !url.contains("{z}") || !url.contains("{x}") || !url.contains("{y}")) {
			throw new ServiceException(MSG_TEMPLATE);
		}
		List<String> subs = new ArrayList<>();
		for (String s : t.subdomains()) {
			String v = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
			if (!SUBDOMAIN.matcher(v).matches()) {
				throw new ServiceException(MSG_SUBDOMAINS);
			}
			subs.add(v);
		}
		if (subs.size() > 8 || (url.contains("{s}") && subs.isEmpty())) {
			throw new ServiceException(MSG_SUBDOMAINS);
		}
		String attribution = t.attribution().replaceAll("[<>]", "").trim();
		if (attribution.length() > 200) {
			throw new ServiceException(MSG_ATTRIBUTION);
		}
		GisXyzTemplate clean = new GisXyzTemplate(url, subs, attribution, Math.max(1, Math.min(t.maxZoom(), 22)));
		List<String> samples = subs.isEmpty() ? List.of("") : subs;
		for (String sub : samples) {
			String probe = clean.url().replace("{s}", sub);
			checkUri(probe.replace("{z}", "1").replace("{x}", "0").replace("{y}", "0").replace("{key}", "k"), allowedHosts);
		}
		return clean;
	}

	/** 出站前对具体地址的静态校验（不解析 DNS） */
	public static URI checkUri(String raw, List<String> allowedHosts) {
		URI uri;
		try {
			uri = new URI(raw);
		} catch (Exception e) {
			throw new ServiceException(MSG_TEMPLATE);
		}
		if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
			throw new ServiceException(MSG_TEMPLATE);
		}
		if (uri.getPort() != -1 && uri.getPort() != 443) {
			throw new ServiceException(MSG_TEMPLATE);
		}
		String host = uri.getHost();
		if (host == null || host.isBlank()) {
			throw new ServiceException(MSG_TEMPLATE);
		}
		host = host.toLowerCase(Locale.ROOT);
		if (isIpLiteral(host)) {
			throw new ServiceException(MSG_IP);
		}
		if (!HOSTNAME.matcher(host).matches() || !hostAllowed(host, allowedHosts)) {
			throw new ServiceException(MSG_HOST);
		}
		return uri;
	}

	public static boolean isIpLiteral(String host) {
		if (host == null) {
			return false;
		}
		String h = host.trim();
		return h.startsWith("[") || h.contains(":") || IPV4.matcher(h).matches()
			// 0x7f.1、2130706433 这类变体也按 IP 处理
			|| h.matches("^[0-9.]+$") || h.matches("^(0x[0-9a-f]+\\.?)+$");
	}

	public static boolean hostAllowed(String host, List<String> allowedHosts) {
		if (host == null || allowedHosts == null) {
			return false;
		}
		String h = host.toLowerCase(Locale.ROOT);
		for (String a : allowedHosts) {
			if (a == null || a.isBlank()) {
				continue;
			}
			String allow = a.trim().toLowerCase(Locale.ROOT);
			if (h.equals(allow) || h.endsWith("." + allow)) {
				return true;
			}
		}
		return false;
	}

	/** 解析后逐个地址校验；任一落在非公网段即拒绝（防 DNS 指向内网） */
	public static void checkResolved(String host, Resolver resolver) {
		InetAddress[] addrs;
		try {
			addrs = resolver.resolve(host);
		} catch (UnknownHostException e) {
			throw new ServiceException(MSG_PRIVATE);
		}
		if (addrs == null || addrs.length == 0) {
			throw new ServiceException(MSG_PRIVATE);
		}
		for (InetAddress addr : addrs) {
			if (isBlocked(addr)) {
				throw new ServiceException(MSG_PRIVATE);
			}
		}
	}

	public static boolean isBlocked(InetAddress addr) {
		if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()
			|| addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
			return true;
		}
		byte[] b = addr.getAddress();
		if (addr instanceof Inet4Address) {
			return blockedV4(b);
		}
		if (addr instanceof Inet6Address) {
			int b0 = b[0] & 0xff;
			if ((b0 & 0xfe) == 0xfc) {
				return true; // fc00::/7 唯一本地
			}
			boolean mapped = true;
			for (int i = 0; i < 10; i++) {
				if (b[i] != 0) {
					mapped = false;
					break;
				}
			}
			if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
				return blockedV4(new byte[] { b[12], b[13], b[14], b[15] });
			}
			if (b0 == 0x20 && (b[1] & 0xff) == 0x01 && b[2] == 0x0d && (b[3] & 0xff) == 0xb8) {
				return true; // 2001:db8::/32 文档段
			}
			if (b0 == 0x00) {
				return true; // ::/8 保留（含 IPv4 兼容地址）
			}
		}
		return false;
	}

	private static boolean blockedV4(byte[] b) {
		int a0 = b[0] & 0xff;
		int a1 = b[1] & 0xff;
		return a0 == 0 || a0 == 10 || a0 == 127 || a0 >= 224
			|| (a0 == 100 && a1 >= 64 && a1 <= 127)   // CGNAT
			|| (a0 == 169 && a1 == 254)               // 链路本地 / 云元数据
			|| (a0 == 172 && a1 >= 16 && a1 <= 31)
			|| (a0 == 192 && a1 == 168)
			|| (a0 == 192 && a1 == 0 && (b[2] & 0xff) == 0);
		// 198.18.0.0/15 不拦：Clash / Surge 等 fake-ip 代理会把公网域名解析到该段，主机名已过白名单
	}
}
