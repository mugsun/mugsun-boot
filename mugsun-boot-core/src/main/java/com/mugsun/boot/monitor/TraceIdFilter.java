package com.mugsun.boot.monitor;

import cn.hutool.core.util.IdUtil;
import com.mugsun.boot.common.constant.MonitorConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 全站链路追踪过滤器（@Order 最前于 XssFilter 的 -100，全站单一 traceId 源头）：
 * 入口 {@code X-Trace-Id} 仅当完全匹配 {@code [A-Za-z0-9-]{1,64}} 时沿用（网关预留），
 * 否则丢弃并生成，防日志/MDC 注入；MDC 放入供日志 pattern 输出；
 * 响应头回写供前后端排障对照；finally 严格清除防线程复用串号。
 * <p>异步透传由 {@code TenantTaskDecorator} 同源传播 MDC（G68 租户透传不回归）。
 */
@Component
@Order(-200)
public class TraceIdFilter extends OncePerRequestFilter {

	/** 客户端 X-Trace-Id 白名单：字母数字与连字符，1–64 位（#98） */
	private static final Pattern CLIENT_TRACE_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		String traceId = request.getHeader(MonitorConstants.TRACE_HEADER);
		if (traceId == null || !CLIENT_TRACE_ID.matcher(traceId).matches()) {
			traceId = IdUtil.fastSimpleUUID();
		}
		MDC.put(MonitorConstants.TRACE_MDC_KEY, traceId);
		response.setHeader(MonitorConstants.TRACE_HEADER, traceId);
		try {
			chain.doFilter(request, response);
		} finally {
			MDC.remove(MonitorConstants.TRACE_MDC_KEY);
		}
	}
}
