package com.mugsun.boot.ai.support;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiSecret;
import com.mugsun.core.tool.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenAPI 加固：IP 白名单（真 CIDR，IPv4/IPv6）+ 可信代理 XFF 解析 + 简易速率限制（按密钥滑动分钟窗口）。
 */
public final class OpenApiGate {

	private static final Map<Long, Window> WINDOWS = new ConcurrentHashMap<>();

	private static volatile List<IpCidr> trustedProxies = List.of();

	private OpenApiGate() {
	}

	public static void checkIpAndRate(AiSecret secret, HttpServletRequest request) {
		String ip = clientIp(request);
		checkAllowIps(secret.getAllowIps(), ip);
		checkRate(secret, ip);
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
				// 非法值不可信，停止回溯，退回最后一个可信代理地址
				return remote;
			}
			if (!IpCidr.anyContains(proxies, h)) {
				return h;
			}
		}
		return remote;
	}

	/** 可信代理（CIDR 列表）；默认空 = 不信任任何转发头。 */
	public static void setTrustedProxies(String raw) {
		trustedProxies = List.copyOf(IpCidr.parseList(raw));
	}

	static void checkAllowIps(String allowIps, String ip) {
		if (allowIps == null || allowIps.isBlank()) {
			return;
		}
		for (String p : allowIps.split("[,;\\s]+")) {
			if ("*".equals(p.trim())) {
				return;
			}
		}
		if (IpCidr.anyContains(IpCidr.parseList(allowIps), ip)) {
			return;
		}
		throw new ServiceException(AiConstants.MSG_OPENAPI_AUTH);
	}

	static void checkRate(AiSecret secret, String ip) {
		Integer limit = secret.getRateLimit();
		if (limit == null || limit <= 0) {
			return;
		}
		long minute = System.currentTimeMillis() / 60_000L;
		Window w = WINDOWS.compute(secret.getId(), (id, old) -> {
			if (old == null || old.minute != minute) {
				return new Window(minute, new AtomicInteger(0));
			}
			return old;
		});
		int n = w.count.incrementAndGet();
		if (n > limit) {
			throw new ServiceException("请求过于频繁，请稍后重试");
		}
	}

	private record Window(long minute, AtomicInteger count) {
	}
}
