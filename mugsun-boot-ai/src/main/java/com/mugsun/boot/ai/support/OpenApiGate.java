package com.mugsun.boot.ai.support;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.entity.AiSecret;
import com.mugsun.core.tool.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenAPI 加固：IP 白名单 + 简易速率限制（按密钥滑动分钟窗口）。
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
		if (request == null) {
			return "";
		}
		String xff = request.getHeader("X-Forwarded-For");
		if (xff != null && !xff.isBlank()) {
			return xff.split(",")[0].trim();
		}
		String real = request.getHeader("X-Real-IP");
		if (real != null && !real.isBlank()) {
			return real.trim();
		}
		return request.getRemoteAddr() == null ? "" : request.getRemoteAddr();
	}

	static void checkAllowIps(String allowIps, String ip) {
		if (allowIps == null || allowIps.isBlank()) {
			return;
		}
		String[] parts = allowIps.split("[,;\\s]+");
		for (String p : parts) {
			if (p.isBlank()) {
				continue;
			}
			if (p.equals(ip) || "*".equals(p) || "0.0.0.0/0".equals(p)) {
				return;
			}
			if (p.endsWith("/0")) {
				return;
			}
			// 简易前缀：1.2.3.* 或 1.2.3.0/24（按前三段）
			if (p.endsWith(".*") && ip.startsWith(p.substring(0, p.length() - 1))) {
				return;
			}
			if (p.contains("/") && ip.startsWith(p.substring(0, p.indexOf('.')))) {
				String cidrBase = p.substring(0, p.indexOf('/'));
				if (ip.startsWith(cidrBase.substring(0, Math.max(0, cidrBase.lastIndexOf('.'))))) {
					return;
				}
			}
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
