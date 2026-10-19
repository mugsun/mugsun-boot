package com.mugsun.boot.demo;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 只读演示模式的判定规则（纯函数，无 Spring 依赖，便于全量端点测试）。
 * <ul>
 *   <li>默认拒绝：GET/HEAD/OPTIONS 之外的方法只放行 {@link #WRITE_ALLOWLIST} 中精确列出的路径；新加的写端点默认就被拒。</li>
 *   <li>{@link #ALWAYS_BLOCKED_PREFIXES} 下任何方法都拒（含 GET）：开放平台 /open、OAuth2 授权（GET /authorize 会签发授权码）、
 *       AI OpenAI 兼容接口 /v1、国密演示 /system/crypto、社交登录 /auth/social、AI 管理 /system/ai。</li>
 *   <li>带 X-HTTP-Method-Override 等方法覆盖头或 {@code _method} 参数的请求一律拒绝，防止 GET 伪装写方法。</li>
 *   <li>路径先规范化（去 context-path、去 {@code ;} 矩阵参数、URL 解码、合并重复斜杠、去尾斜杠）再匹配；
 *       含 {@code ..}、反斜杠或解码失败的路径一律拒绝。</li>
 * </ul>
 */
public final class DemoReadOnlyPolicy {

	/** 安全方法：只读，放行（仍受 ALWAYS_BLOCKED_PREFIXES 约束） */
	public static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

	/** 登录/登出/验证码：演示账号必须能登录 */
	public static final Set<String> AUTH_ALLOWLIST = Set.of(
		"/auth/login",
		"/auth/logout",
		"/auth/two-factor",
		"/app/auth/login",
		"/app/auth/logout",
		"/app/auth/two-factor",
		"/app/captcha/check");

	/** 埋点采集（演示环境保持开启，写独立埋点库、独立账号）；mugsun.demo.track-collect=false 时不放行 */
	public static final Set<String> TRACK_COLLECT_ALLOWLIST = Set.of(
		"/track/collect",
		"/track/replay",
		"/track/api-body");

	/**
	 * 只读查询型 POST。当前代码库没有以 POST 承载的纯查询接口（已逐个核对 298 个写映射），故为空；
	 * 以后新增时必须在此逐个列出并在测试里说明理由。
	 */
	public static final Set<String> READ_ONLY_POST_ALLOWLIST = Set.of();

	/** 任何方法都拒绝的前缀（段感知匹配） */
	public static final List<String> ALWAYS_BLOCKED_PREFIXES = List.of(
		"/open",
		"/oauth2",
		"/v1",
		"/system/crypto",
		// 安全复审 F4：社交登录（render 写 state、mock-authorize）与 AI（可能访问外部模型）在演示环境整体关闭
		"/auth/social",
		"/system/ai");

	/** 方法覆盖头：存在即拒绝 */
	public static final List<String> METHOD_OVERRIDE_HEADERS = List.of(
		"X-HTTP-Method-Override", "X-HTTP-Method", "X-Method-Override");

	/** 方法覆盖参数 */
	public static final String METHOD_OVERRIDE_PARAM = "_method";

	private final Set<String> writeAllowlist;

	public DemoReadOnlyPolicy(boolean trackCollect) {
		Set<String> s = new LinkedHashSet<>(AUTH_ALLOWLIST);
		s.addAll(READ_ONLY_POST_ALLOWLIST);
		if (trackCollect) {
			s.addAll(TRACK_COLLECT_ALLOWLIST);
		}
		this.writeAllowlist = Set.copyOf(s);
	}

	/** 实际生效的写白名单（只读视图） */
	public Set<String> writeAllowlist() {
		return writeAllowlist;
	}

	/**
	 * @param method        HTTP 方法
	 * @param path          已去 context-path 的请求路径（未规范化）
	 * @param methodOverride 请求是否携带方法覆盖头/参数
	 * @return true 放行；false 拒绝（演示环境只读）
	 */
	public boolean isAllowed(String method, String path, boolean methodOverride) {
		if (methodOverride || method == null) {
			return false;
		}
		String normalized = normalize(path);
		if (normalized == null) {
			return false;
		}
		String lower = normalized.toLowerCase(Locale.ROOT);
		for (String prefix : ALWAYS_BLOCKED_PREFIXES) {
			if (lower.equals(prefix) || lower.startsWith(prefix + "/")) {
				return false;
			}
		}
		String m = method.toUpperCase(Locale.ROOT);
		if (SAFE_METHODS.contains(m)) {
			return true;
		}
		// 写白名单只放 POST：登录/采集接口均为 POST，PUT/DELETE/PATCH 不在任何白名单内
		return "POST".equals(m) && writeAllowlist.contains(normalized);
	}

	/**
	 * 规范化路径；返回 null 表示可疑（含 ..、反斜杠、解码失败、空字节），调用方应拒绝。
	 */
	public static String normalize(String path) {
		if (path == null || path.isEmpty()) {
			return "/";
		}
		StringBuilder noMatrix = new StringBuilder();
		for (String seg : path.split("/", -1)) {
			int semi = seg.indexOf(';');
			noMatrix.append(semi >= 0 ? seg.substring(0, semi) : seg).append('/');
		}
		String p = noMatrix.substring(0, noMatrix.length() - 1);
		try {
			// 解码两次以覆盖 %252F 之类的二次编码；解码后再判定可疑字符
			p = URLDecoder.decode(p.replace("+", "%2B"), StandardCharsets.UTF_8);
			p = URLDecoder.decode(p.replace("+", "%2B"), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException e) {
			return null;
		}
		if (p.indexOf('\\') >= 0 || p.indexOf('\0') >= 0 || p.indexOf(';') >= 0) {
			return null;
		}
		p = p.replaceAll("/{2,}", "/");
		if (!p.startsWith("/")) {
			p = "/" + p;
		}
		for (String seg : p.split("/")) {
			if (seg.equals("..") || seg.equals(".")) {
				return null;
			}
		}
		if (p.length() > 1 && p.endsWith("/")) {
			p = p.substring(0, p.length() - 1);
		}
		return p;
	}
}
