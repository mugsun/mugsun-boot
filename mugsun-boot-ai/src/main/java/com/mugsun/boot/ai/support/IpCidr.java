package com.mugsun.boot.ai.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 真正的 CIDR 匹配（IPv4 / IPv6，按位掩码）。单 IP 视为 /32 或 /128；非法条目忽略并 warn。
 * 仅接受字面量 IP，不做 DNS 解析。
 */
public final class IpCidr {

	private static final Logger log = LoggerFactory.getLogger(IpCidr.class);

	private final byte[] network;
	private final int prefix;

	private IpCidr(byte[] network, int prefix) {
		this.network = network;
		this.prefix = prefix;
	}

	/** 解析单条；非法返回 null。 */
	public static IpCidr parse(String raw) {
		if (raw == null) {
			return null;
		}
		String s = raw.trim();
		if (s.isEmpty()) {
			return null;
		}
		String addr = s;
		Integer pfx = null;
		int slash = s.indexOf('/');
		if (slash >= 0) {
			addr = s.substring(0, slash);
			try {
				pfx = Integer.parseInt(s.substring(slash + 1));
			} catch (NumberFormatException e) {
				return null;
			}
		}
		byte[] bytes = toBytes(addr);
		if (bytes == null) {
			return null;
		}
		int max = bytes.length * 8;
		int p = pfx == null ? max : pfx;
		if (p < 0 || p > max) {
			return null;
		}
		return new IpCidr(mask(bytes, p), p);
	}

	/** 解析列表（逗号/分号/空白分隔），非法条目 warn 后忽略。 */
	public static List<IpCidr> parseList(String raw) {
		if (raw == null || raw.isBlank()) {
			return Collections.emptyList();
		}
		List<IpCidr> out = new ArrayList<>();
		for (String p : raw.split("[,;\\s]+")) {
			if (p.isBlank()) {
				continue;
			}
			IpCidr c = parse(p);
			if (c == null) {
				log.warn("忽略非法 IP/CIDR 条目: {}", p);
			} else {
				out.add(c);
			}
		}
		return out;
	}

	public boolean contains(String ip) {
		byte[] b = toBytes(ip == null ? null : ip.trim());
		if (b == null || b.length != network.length) {
			return false;
		}
		return java.util.Arrays.equals(mask(b, prefix), network);
	}

	public static boolean anyContains(List<IpCidr> list, String ip) {
		for (IpCidr c : list) {
			if (c.contains(ip)) {
				return true;
			}
		}
		return false;
	}

	/** 字面量 IP → 字节；IPv4 映射的 IPv6 由 JDK 自动折叠为 4 字节。 */
	static byte[] toBytes(String addr) {
		if (addr == null || addr.isEmpty()) {
			return null;
		}
		String a = addr;
		if (a.startsWith("[") && a.endsWith("]")) {
			a = a.substring(1, a.length() - 1);
		}
		int pct = a.indexOf('%');
		if (pct >= 0) {
			a = a.substring(0, pct);
		}
		boolean v4 = a.matches("\\d{1,3}(\\.\\d{1,3}){3}");
		boolean v6 = a.indexOf(':') >= 0 && a.matches("[0-9a-fA-F:.]+");
		if (!v4 && !v6) {
			return null; // 拒绝主机名，避免 DNS 解析
		}
		try {
			return InetAddress.getByName(a).getAddress();
		} catch (Exception e) {
			return null;
		}
	}

	private static byte[] mask(byte[] in, int p) {
		byte[] out = in.clone();
		for (int i = 0; i < out.length; i++) {
			int bits = Math.max(0, Math.min(8, p - i * 8));
			out[i] = (byte) (out[i] & (bits == 0 ? 0 : (0xFF << (8 - bits))));
		}
		return out;
	}
}
