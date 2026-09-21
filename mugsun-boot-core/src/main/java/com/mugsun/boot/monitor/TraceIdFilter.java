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

/**
 * 全站链路追踪过滤器（@Order 最前于 XssFilter 的 -100，全站单一 traceId 源头）：
 * 入口携带 {@code X-Trace-Id} 则沿用（网关预留），否则生成；MDC 放入供日志 pattern 输出；
 * 响应头回写供前后端排障对照；finally 严格清除防线程复用串号。
 * <p>异步透传由 {@code TenantTaskDecorator} 同源传播 MDC（G68 租户透传不回归）。
 */
@Component
@Order(-200)
public class TraceIdFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws ServletException, IOException {
		String traceId = request.getHeader(MonitorConstants.TRACE_HEADER);
		if (traceId == null || traceId.isBlank() || traceId.length() > 64) {
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
