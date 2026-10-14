package com.mugsun.boot.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.api.ResultCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 只读演示模式过滤器（仅 {@code mugsun.demo.enabled=true}，即 demo profile 下装配）。
 * <p>Order -150：在 TraceIdFilter(-200) 之后（拒绝响应也带 traceId），XssFilter(-100) 之前（被拒请求不缓存 body）。
 * 规则见 {@link DemoReadOnlyPolicy}；被拒返回 HTTP 403 +
 * {@code {"code":403,"success":false,"msg":"演示环境只读","errorCode":"DEMO_READONLY"}}。
 */
@Component
@Order(-150)
@ConditionalOnProperty(name = DemoConstants.ENABLED_PROPERTY, havingValue = "true")
public class DemoReadOnlyFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(DemoReadOnlyFilter.class);

	private final DemoReadOnlyPolicy policy;
	private final ObjectMapper objectMapper;

	public DemoReadOnlyFilter(ObjectMapper objectMapper,
							  @Value("${mugsun.demo.track-collect:true}") boolean trackCollect) {
		this.objectMapper = objectMapper;
		this.policy = new DemoReadOnlyPolicy(trackCollect);
		log.warn("[demo] 只读演示模式已开启：非 GET 请求仅放行 {}", policy.writeAllowlist());
	}

	public DemoReadOnlyPolicy policy() {
		return policy;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		if (policy.isAllowed(request.getMethod(), pathWithinApp(request), hasMethodOverride(request))) {
			chain.doFilter(request, response);
			return;
		}
		reject(response, objectMapper);
	}

	/** 异步派发/错误派发也要过滤，防止 forward 到写端点 */
	@Override
	protected boolean shouldNotFilterAsyncDispatch() {
		return false;
	}

	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

	static String pathWithinApp(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String ctx = request.getContextPath();
		if (uri == null) {
			return "/";
		}
		if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) {
			uri = uri.substring(ctx.length());
		}
		return uri;
	}

	static boolean hasMethodOverride(HttpServletRequest request) {
		for (String h : DemoReadOnlyPolicy.METHOD_OVERRIDE_HEADERS) {
			if (request.getHeader(h) != null) {
				return true;
			}
		}
		String q = request.getQueryString();
		return q != null && (q.startsWith(DemoReadOnlyPolicy.METHOD_OVERRIDE_PARAM + "=")
			|| q.contains("&" + DemoReadOnlyPolicy.METHOD_OVERRIDE_PARAM + "="));
	}

	static void reject(HttpServletResponse response, ObjectMapper objectMapper) throws IOException {
		Map<String, Object> body = new LinkedHashMap<>(objectMapper.convertValue(
			R.fail(ResultCode.FORBIDDEN, DemoConstants.MESSAGE), Map.class));
		body.put("errorCode", DemoConstants.ERROR_CODE);
		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(objectMapper.writeValueAsString(body));
	}
}
