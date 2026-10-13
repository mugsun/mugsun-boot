package com.mugsun.boot.ai.support;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiSecret;
import com.mugsun.boot.common.web.ClientIpResolver;
import com.mugsun.boot.common.web.IpCidr;
import com.mugsun.core.tool.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenAPI 加固：IP 白名单（真 CIDR，IPv4/IPv6）+ 可信代理 XFF 解析 + 简易速率限制（按密钥滑动分钟窗口）。
 */
public final class OpenApiGate {

	private static final Map<Long, Window> WINDOWS = new ConcurrentHashMap<>();

	private OpenApiGate() {
	}

	public static void checkIpAndRate(AiSecret secret, HttpServletRequest request) {
		String ip = clientIp(request);
		checkAllowIps(secret.getAllowIps(), ip);
		checkRate(secret, ip);
	}

	public static String clientIp(HttpServletRequest request) {
		return ClientIpResolver.clientIp(request);
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
