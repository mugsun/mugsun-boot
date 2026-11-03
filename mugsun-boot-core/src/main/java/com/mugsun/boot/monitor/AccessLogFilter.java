package com.mugsun.boot.monitor;

import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.MonitorConstants;
import com.mugsun.boot.common.web.ClientIpResolver;
import com.mugsun.boot.demo.DemoConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;

/**
 * 只读演示模式的访问日志（留存 ≥6 个月，《网络安全法》第二十一条）：每个请求一行，写专用 logger {@value #LOGGER}，
 * 由 logback-spring.xml 里 demo profile 的 ACCESS 文件通道按天滚动、gzip、默认保留 200 天。
 * <ul>
 *   <li>与 {@link ApiLogFilter} 不同：不采样、不排除路径（埋点、WebSocket 握手、上传都记），不落库，不受 LogCleanJob 影响；</li>
 *   <li>只记 IP、方法、路由模板、状态、耗时、用户、traceId；<b>不记 query、body 与原始路径</b>
 *       （路径变量里可能有令牌/签名；WebSocket 等地址的 query 也带 token）；</li>
 *   <li>IP 按 {@link ClientIpResolver}（可信代理才读 X-Forwarded-For），与登录锁定、限流同一口径。</li>
 * </ul>
 * Order -95：在 TraceIdFilter（-200，写 MDC）和 Spring 的 RequestContextFilter（-105）之内，结束时还能取到登录用户。
 * 日志写在 {@code chain.doFilter} 之后，以便读取 DispatcherServlet 写入的
 * {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}。
 */
@Component
@Order(-95)
@ConditionalOnProperty(name = DemoConstants.ENABLED_PROPERTY, havingValue = "true")
public class AccessLogFilter extends OncePerRequestFilter {

	static final String LOGGER = "ACCESS";
	/** 拿不到路由模板时（404、未匹配的静态资源等）的固定占位，避免把原始路径写进日志 */
	static final String UNMATCHED = "(unmatched)";
	private static final Logger ACCESS = LoggerFactory.getLogger(LOGGER);

	@Override
	protected boolean shouldNotFilterAsyncDispatch() {
		return true;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		long start = System.nanoTime();
		int status = 0;
		try {
			chain.doFilter(request, response);
			status = response.getStatus();
		} catch (IOException | ServletException | RuntimeException e) {
			status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
			throw e;
		} finally {
			try {
				ACCESS.info(line(request, status, (System.nanoTime() - start) / 1_000_000, userId()));
			} catch (RuntimeException ignore) {
				// 访问日志尽力而为，不反噬请求
			}
		}
	}

	static String line(HttpServletRequest request, int status, long millis, String userId) {
		return "ip=" + clean(ClientIpResolver.clientIp(request))
			+ " method=" + clean(request.getMethod())
			+ " uri=" + clean(routeTemplate(request))
			+ " status=" + status
			+ " ms=" + millis
			+ " user=" + clean(userId)
			+ " trace=" + clean(MDC.get(MonitorConstants.TRACE_MDC_KEY));
	}

	private static String userId() {
		try {
			Object id = StpUtil.getLoginIdDefaultNull();
			return id == null ? null : id.toString();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * 访问日志只记 Spring 路由模板（如 {@code /share/{token}}、{@code /system/file/download/{id}}），
	 * 不记原始路径，避免路径变量里的令牌/签名泄露。
	 * <p>DispatcherServlet（及 ResourceHttpRequestHandler）匹配成功后写入
	 * {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}；拿不到时（404、过滤器直接拦截等）
	 * 返回 {@link #UNMATCHED}，绝不回退到 {@link HttpServletRequest#getRequestURI()}。
	 */
	static String routeTemplate(HttpServletRequest request) {
		Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		if (pattern instanceof String s && !s.isEmpty()) {
			return s;
		}
		return UNMATCHED;
	}

	/** 去掉空白与控制字符（防日志注入），空值记为 - ，单值截到 512 */
	static String clean(String v) {
		if (v == null || v.isEmpty()) {
			return "-";
		}
		StringBuilder sb = new StringBuilder(Math.min(v.length(), 512));
		for (int i = 0; i < v.length() && sb.length() < 512; i++) {
			char c = v.charAt(i);
			sb.append(Character.isWhitespace(c) || Character.isISOControl(c) ? '_' : c);
		}
		return sb.toString();
	}
}
