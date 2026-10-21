package com.mugsun.boot.security;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.api.ResultCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * multipart 前置闸门：在任何会读参数或请求体的 Filter 之前，拒绝未登录或不在白名单的 multipart 请求。
 * <p>背景：Sa-Token 的 {@code SaInterceptor} 是 MVC 拦截器，DispatcherServlet 在 checkMultipart() 里先解析
 * multipart（读满请求体、写临时文件）才轮到它；XssFilter、DemoReadOnlyFilter、LocalFileAccessFilter 等调用
 * getParameter 也会提前触发 Tomcat 解析。所以这里排在 HIGHEST_PRECEDENCE，只看请求行和请求头。
 * <ul>
 *   <li>非 multipart：直接放行。</li>
 *   <li>multipart 且路径不在白名单：415。</li>
 *   <li>白名单路径但 token 无效：401。具体权限仍由接口上的 Sa-Token 注解校验。</li>
 * </ul>
 * 拒绝时本过滤器不调用 getInputStream / getParts，并带 {@code Connection: close}：未登录的 multipart 不会被解析、
 * 不会写磁盘。但在 server.tomcat.max-swallow-size 以内，容器仍可能读完请求体再丢弃，所以部署时务必在反代
 * 设置请求体上限（约 21MB）。token 只从请求头和 Cookie 取，不读请求参数
 * （读参数本身就会触发 multipart 解析）。
 */
@Component
@Order(MultipartAuthFilter.ORDER)
public class MultipartAuthFilter extends OncePerRequestFilter {

	/** 全站最先执行（与 Spring Boot 的 CharacterEncodingFilter 并列，后者只设编码、不读参数） */
	public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

	/** 启动时固定下来的白名单（配置不支持热更新，不必每个请求复制） */
	private final Set<String> allowedPaths;
	private final ObjectMapper objectMapper;

	public MultipartAuthFilter(MultipartGuardProperties properties, ObjectMapper objectMapper) {
		this.allowedPaths = Set.copyOf(properties.getAllowedPaths());
		this.objectMapper = objectMapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		if (!isMultipart(request.getContentType())) {
			chain.doFilter(request, response);
			return;
		}
		if (!isAllowedPath(request, allowedPaths)) {
			reject(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
				R.fail(ResultCode.FAILURE, "该接口不接受 multipart 请求"));
			return;
		}
		if (!hasValidToken(request)) {
			reject(response, HttpServletResponse.SC_UNAUTHORIZED, R.fail(ResultCode.UNAUTHORIZED));
			return;
		}
		chain.doFilter(request, response);
	}

	/** 不区分大小写，覆盖 multipart/form-data、multipart/mixed 等所有 multipart/* */
	static boolean isMultipart(String contentType) {
		return contentType != null && contentType.strip().toLowerCase(Locale.ROOT).startsWith("multipart/");
	}

	/**
	 * 精确比对：servletPath（容器已解码、去 ;参数、合并斜杠）必须在白名单里，且原始 requestURI 必须与
	 * contextPath + servletPath 逐字相同。这样末尾斜杠、;参数、%2F 等编码、双斜杠、大小写变体都不会命中。
	 */
	static boolean isAllowedPath(HttpServletRequest request, Set<String> allowed) {
		String servletPath = request.getServletPath();
		if (servletPath == null || request.getPathInfo() != null || !allowed.contains(servletPath)) {
			return false;
		}
		String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
		return (contextPath + servletPath).equals(request.getRequestURI());
	}

	private boolean hasValidToken(HttpServletRequest request) {
		String token = readToken(request);
		if (token == null || token.isBlank()) {
			return false;
		}
		try {
			return StpUtil.getLoginIdByToken(token) != null;
		} catch (RuntimeException e) {
			return false;
		}
	}

	/** 与 Sa-Token 读取顺序一致（请求头优先，其次 Cookie），但不读请求参数 */
	private static String readToken(HttpServletRequest request) {
		String name = SaManager.getConfig().getTokenName();
		String header = request.getHeader(name);
		if (header != null && !header.isBlank()) {
			return stripPrefix(header.strip());
		}
		Cookie[] cookies = request.getCookies();
		if (cookies != null) {
			for (Cookie c : cookies) {
				if (name.equals(c.getName())) {
					return stripPrefix(c.getValue());
				}
			}
		}
		return null;
	}

	private static String stripPrefix(String value) {
		String prefix = SaManager.getConfig().getTokenPrefix();
		if (prefix == null || prefix.isEmpty() || value == null) {
			return value;
		}
		String p = prefix + " ";
		return value.startsWith(p) ? value.substring(p.length()) : null;
	}

	private void reject(HttpServletResponse response, int status, R<Void> body) throws IOException {
		response.setStatus(status);
		response.setHeader(HttpHeaders.CONNECTION, "close");
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(objectMapper.writeValueAsString(body));
	}
}
