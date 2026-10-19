package com.mugsun.boot.track;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 来源域名（外部来源）：
 * <ul>
 * <li>props.referrer_domain 优先（自定义上报端可直接给），同样归一小写；</li>
 * <li>否则从 props.referrer（完整 URL，Web SDK 上报 document.referrer）取主机名：先按 {@link URI} 解析，
 * 失败（如 query 里有空格、未编码的 |、[ 等，浏览器照样会给）再用宽松正则取 scheme://[userinfo@]host；</li>
 * <li>只认 http(s)；小写、去末尾点、去端口；不剥 www.，不合并子域；</li>
 * <li><b>同站来源不算来源</b>：主机名等于页面主机（上报请求的 Origin，缺省取 Referer 头）时返回 null，
 * 站内跳转不会让来源维度全是自己的域名。</li>
 * </ul>
 * track_app 目前没有配置站点域名的字段，所以「同站」只按页面主机精确比较（不含子域）。
 */
final class TrackReferrer {

	/** URI 解析失败时的宽松取主机：scheme://[userinfo@]host[:port]... */
	private static final Pattern LENIENT_HOST = Pattern.compile(
		"^\\s*(https?)://(?:[^@/?#\\s]*@)?([^/?#:\\s\\\\]+)", Pattern.CASE_INSENSITIVE);

	private TrackReferrer() {
	}

	/** 兼容旧调用：不做同站排除 */
	static String domainOf(String referrerDomain, String referrer) {
		return domainOf(referrerDomain, referrer, null);
	}

	/**
	 * @param pageHost 页面主机（小写、无端口），null 表示未知、不做同站排除
	 */
	static String domainOf(String referrerDomain, String referrer, String pageHost) {
		String host;
		if (referrerDomain != null && !referrerDomain.isBlank()) {
			host = normalize(referrerDomain.trim());
		} else {
			host = hostOf(referrer);
		}
		if (host == null) {
			return null;
		}
		if (pageHost != null && host.equals(pageHost)) {
			return null;
		}
		return host;
	}

	/** 任意 URL（Origin / Referer 头或 props.referrer）的 http(s) 主机名，归一后；取不到返回 null */
	static String hostOf(String url) {
		if (url == null || url.isBlank()) {
			return null;
		}
		String raw = url.trim();
		try {
			URI uri = new URI(raw);
			String scheme = uri.getScheme();
			if (scheme == null) {
				return null;
			}
			if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
				return null;
			}
			if (uri.getHost() != null) {
				return normalize(uri.getHost());
			}
		} catch (Exception e) {
			// 落到宽松正则
		}
		Matcher m = LENIENT_HOST.matcher(raw);
		return m.find() ? normalize(m.group(2)) : null;
	}

	private static String normalize(String host) {
		String h = host.toLowerCase(Locale.ROOT);
		while (h.endsWith(".")) {
			h = h.substring(0, h.length() - 1);
		}
		return h.isEmpty() ? null : h;
	}
}
