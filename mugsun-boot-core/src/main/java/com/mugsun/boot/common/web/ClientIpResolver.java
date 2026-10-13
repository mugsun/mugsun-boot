package com.mugsun.boot.common.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * 客户端 IP 解析：默认只用 remoteAddr；仅当 remoteAddr 属于可信代理（mugsun.openapi.trusted-proxies）时，
 * 才从 X-Forwarded-For 右往左取第一个不在可信代理内的地址。X-Real-IP 不信任。
 */
public final class ClientIpResolver {

	private static volatile List<IpCidr> trustedProxies = List.of();

	private ClientIpResolver() {
	}

	/** 可信代理（CIDR 列表）；默认空 = 不信任任何转发头。 */
	public static void setTrustedProxies(String raw) {
		trustedProxies = List.copyOf(IpCidr.parseList(raw));
	}

	public static String clientIp(HttpServletRequest request) {
		if (request == null) {
			return "";
		}
		String remote = request.getRemoteAddr() == null ? "" : request.getRemoteAddr().trim();
		List<IpCidr> proxies = trustedProxies;
		if (proxies.isEmpty() || !IpCidr.anyContains(proxies, remote)) {
			return remote;
		}
		String xff = request.getHeader("X-Forwarded-For");
		if (xff == null || xff.isBlank()) {
			return remote;
		}
		String[] hops = xff.split(",");
		for (int i = hops.length - 1; i >= 0; i--) {
			String h = hops[i].trim();
			if (h.isEmpty()) {
				continue;
			}
			if (IpCidr.toBytes(h) == null) {
				// 非法值不可信，停止回溯
				return remote;
			}
			if (!IpCidr.anyContains(proxies, h)) {
				return h;
			}
		}
		return remote;
	}
}
