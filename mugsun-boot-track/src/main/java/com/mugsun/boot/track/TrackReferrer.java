package com.mugsun.boot.track;

import java.net.URI;
import java.util.Locale;

/**
 * 来源域名：props.referrer_domain 优先（自定义上报端可直接给）；否则从 props.referrer（完整 URL，
 * Web SDK 上报 document.referrer）取主机名，小写、去掉末尾的点。非 http(s)、无主机或解析失败返回 null。
 * 不剥 www.，不合并子域，与浏览器地址栏的主机名一致。
 */
final class TrackReferrer {

	private TrackReferrer() {
	}

	static String domainOf(String referrerDomain, String referrer) {
		if (referrerDomain != null && !referrerDomain.isBlank()) {
			return referrerDomain.trim();
		}
		if (referrer == null || referrer.isBlank()) {
			return null;
		}
		try {
			URI uri = new URI(referrer.trim());
			String scheme = uri.getScheme();
			if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
				return null;
			}
			String host = uri.getHost();
			if (host == null) {
				return null;
			}
			host = host.toLowerCase(Locale.ROOT);
			while (host.endsWith(".")) {
				host = host.substring(0, host.length() - 1);
			}
			return host.isEmpty() ? null : host;
		} catch (Exception e) {
			return null;
		}
	}
}
