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

import java.io.IOException;

/**
 * 只读演示模式的访问日志（留存 ≥6 个月，《网络安全法》第二十一条）：每个请求一行，写专用 logger {@value #LOGGER}，
 * 由 logback-spring.xml 里 demo profile 的 ACCESS 文件通道按天滚动、gzip、默认保留 200 天。
 * <ul>
 *   <li>与 {@link ApiLogFilter} 不同：不采样、不排除路径（埋点、WebSocket 握手、上传都记），不落库，不受 LogCleanJob 影响；</li>
 *   <li>只记 IP、方法、路径、状态、耗时、用户、traceId；<b>不记 query 和 body</b>（WebSocket 等地址的 query 带 token）；</li>
 *   <li>IP 按 {@link ClientIpResolver}（可信代理才读 X-Forwarded-For），与登录锁定、限流同一口径。</li>
 * </ul>
 * Order -95：在 TraceIdFilter（-200，写 MDC）和 Spring 的 RequestContextFilter（-105）之内，结束时还能取到登录用户。
 */
@Component
@Order(-95)
@ConditionalOnProperty(name = DemoConstants.ENABLED_PROPERTY, havingValue = "true")
public class AccessLogFilter extends OncePerRequestFilter {

	static final String LOGGER = "ACCESS";
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
			+ " uri=" + clean(redactPath(request.getRequestURI()))
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

	private static final java.util.regex.Pattern TOKEN_LIKE =
		java.util.regex.Pattern.compile("(?=.*[0-9])(?=.*[A-Za-z])[A-Za-z0-9_\\-.~=%]{24,}|(?=.*[0-9])[0-9a-fA-F]{32,}");

	/**
	 * 路径脱敏：去掉每段 {@code ;} 之后的 matrix 参数（{@code ;jsessionid=} 等）；看起来像令牌或签名的段
	 * （≥24 位字母数字混合，或 ≥32 位十六进制）换成 {@code :redacted}，签名下载地址只留前缀。
	 */
	static String redactPath(String uri) {
		if (uri == null || uri.isEmpty()) {
			return uri;
		}
		String[] segs = uri.split("/", -1);
		StringBuilder sb = new StringBuilder(uri.length());
		for (int i = 0; i < segs.length; i++) {
			String seg = segs[i];
			int semi = seg.indexOf(';');
			if (semi >= 0) {
				seg = seg.substring(0, semi);
			}
			if (TOKEN_LIKE.matcher(seg).matches()) {
				seg = ":redacted";
			}
			if (i > 0) {
				sb.append('/');
			}
			sb.append(seg);
		}
		return sb.toString();
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
